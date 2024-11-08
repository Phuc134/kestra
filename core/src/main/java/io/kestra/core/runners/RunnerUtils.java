package io.kestra.core.runners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.encryption.EncryptionService;
import io.kestra.core.exceptions.MissingRequiredArgument;
import io.kestra.core.models.Label;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Data;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.Input;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.flows.Type;
import io.kestra.core.models.tasks.common.EncryptedString;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.services.ConditionService;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.Await;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.ListUtils;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.kestra.core.utils.Rethrow.throwFunction;

@Singleton
public class RunnerUtils {
    public static final Pattern URI_PATTERN = Pattern.compile("^[a-z]+:\\/\\/(?:www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_\\+.~#?&\\/=]*)$");

    @Inject
    @Named(QueueFactoryInterface.EXECUTION_NAMED)
    protected QueueInterface<Execution> executionQueue;

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private StorageInterface storageInterface;

    @Inject
    private ConditionService conditionService;

    @Value("${kestra.encryption.secret-key}")
    private Optional<String> secretKey;

    /**
     * Utility method for retrieving types inputs for a flow.
     *
     * @param flow      The Flow.
     * @param execution The Execution.
     * @param inputs        The Flow's inputs.
     * @return The Map of typed inputs.
     */
    public Map<String, Object> typedInputs(final Flow flow,
                                           final Execution execution,
                                           final Publisher<CompletedPart> inputs) throws IOException {
        return this.typedInputs(flow.getInputs(), execution, inputs);
    }

    /**
     * Utility method for retrieving types inputs for a flow.
     *
     * @param inputs      The inputs
     * @param execution The Execution.
     * @param in        The Execution's inputs.
     * @return The Map of typed inputs.
     */
    public Map<String, Object> typedInputs(final List<Input<?>> inputs,
                                           final Execution execution,
                                           final Publisher<CompletedPart> in) throws IOException {
        Map<String, Object> uploads = Flux.from(in)
            .subscribeOn(Schedulers.boundedElastic())
            .map(throwFunction(input -> {
                if (input instanceof CompletedFileUpload fileUpload) {
                    try {
                        File tempFile = File.createTempFile(fileUpload.getFilename() + "_", ".upl");
                        try (var inputStream = fileUpload.getInputStream();
                             var outputStream = new FileOutputStream(tempFile)) {
                            long transferredBytes = inputStream.transferTo(outputStream);
                            if (transferredBytes == 0) {
                                fileUpload.discard();
                                throw new RuntimeException("Can't upload file: " + fileUpload.getFilename());
                            }
                        }
                        URI from = storageInterface.from(execution, fileUpload.getFilename(), tempFile);
                        if (!tempFile.delete()) {
                            tempFile.deleteOnExit();
                        }

                        return new AbstractMap.SimpleEntry<>(
                            fileUpload.getFilename(),
                            (Object) from.toString()
                        );
                    } catch (IOException e) {
                        fileUpload.discard();
                        throw e;
                    }

                } else {
                    return new AbstractMap.SimpleEntry<>(
                        input.getName(),
                        (Object) new String(input.getBytes())
                    );
                }
            }))
            .collectMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)
            .block();

        return this.typedInputs(inputs, execution, uploads);
    }

    /**
     * Utility method for retrieving types inputs for a flow.
     *
     * @param flow      The Flow.
     * @param execution The Execution.
     * @param in        The Execution's inputs.
     * @return The Map of typed inputs.
     */
    public Map<String, Object> typedInputs(
        final Flow flow,
        final Execution execution,
        final Map<String, Object> in
    ) {
        return this.typedInputs(
            flow.getInputs(),
            execution,
            in
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, Object> typedInputs(List<Input<?>> inputs, Execution execution, Map<String, Object> in) {
        Map<String, Object> results = ListUtils.emptyOnNull(inputs)
            .stream()
            .map((Function<Input, Optional<AbstractMap.SimpleEntry<String, Object>>>) input -> {
                Object current = in == null ? null : in.get(input.getId());

                if (current == null && input.getDefaults() != null) {
                    current = input.getDefaults();
                }

                if (input.getRequired() && current == null) {
                    throw new MissingRequiredArgument("Missing required input value '" + input.getId() + "'");
                }

                if (!input.getRequired() && current == null) {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        input.getId(),
                        null
                    ));
                }

                var parsedInput = parseData(execution, input, current);
                parsedInput.ifPresent(parsed -> input.validate(parsed.getValue()));
                return parsedInput;
            })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);

        return handleNestedInputs(results);
    }

    public Map<String, Object> typedOutputs(Flow flow, Execution execution, Map<String, Object> in) {
        if (flow.getOutputs() == null) {
            return ImmutableMap.of();
        }
        Map<String, Object> results = flow
            .getOutputs()
            .stream()
            .map(output -> {
                Object current = in == null ? null : in.get(output.getId());
                return parseData(execution, output, current)
                    .map(entry -> {
                        if (output.getType().equals(Type.SECRET)) {
                            return new AbstractMap.SimpleEntry<>(
                                entry.getKey(),
                                EncryptedString.from(entry.getValue().toString())
                            );
                        }
                        return entry;
                    });
            })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);

        // Ensure outputs are compliant with tasks outputs.
        return JacksonMapper.toMap(results);
    }

    private Optional<AbstractMap.SimpleEntry<String, Object>> parseData(Execution execution, Data data, Object current) {
        if (data.getType() == null) {
            return Optional.of(new AbstractMap.SimpleEntry<>(data.getId(), current));
        }
        final String loggableType = data instanceof Input ? "input" : "output";
        switch (data.getType()) {
            case ENUM, STRING -> {
                return Optional.of(new AbstractMap.SimpleEntry<>(
                    data.getId(),
                    current
                ));
            }
            case SECRET -> {
                try {
                    if (secretKey.isEmpty()) {
                        throw new MissingRequiredArgument("Unable to use a SECRET " + loggableType + " as encryption is not configured");
                    }

                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        EncryptionService.encrypt(secretKey.get(), (String) current)
                    ));
                } catch (GeneralSecurityException e) {
                    throw new MissingRequiredArgument("Invalid SECRET format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case INT -> {
                return Optional.of(new AbstractMap.SimpleEntry<>(
                    data.getId(),
                    current instanceof Integer ? current : Integer.valueOf((String) current)
                ));
            }
            case FLOAT -> {
                return Optional.of(new AbstractMap.SimpleEntry<>(
                    data.getId(),
                    current instanceof Float ? current : Float.valueOf((String) current)
                ));
            }
            case BOOLEAN -> {
                return Optional.of(new AbstractMap.SimpleEntry<>(
                    data.getId(),
                    current instanceof Boolean ? current : Boolean.valueOf((String) current)
                ));
            }
            case DATETIME -> {
                try {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        Instant.parse(((String) current))
                    ));
                } catch (DateTimeParseException e) {
                    throw new MissingRequiredArgument("Invalid DATETIME format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case DATE -> {
                try {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        LocalDate.parse(((String) current))
                    ));
                } catch (DateTimeParseException e) {
                    throw new MissingRequiredArgument("Invalid DATE format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case TIME -> {
                try {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        LocalTime.parse(((String) current))
                    ));
                } catch (DateTimeParseException e) {
                    throw new MissingRequiredArgument("Invalid TIME format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case DURATION -> {
                try {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        Duration.parse(((String) current))
                    ));
                } catch (DateTimeParseException e) {
                    throw new MissingRequiredArgument("Invalid DURATION format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case FILE -> {
                try {
                    URI uri = URI.create(((String) current).replace(File.separator, "/"));

                    if (uri.getScheme() != null && uri.getScheme().equals("kestra")) {
                        return Optional.of(new AbstractMap.SimpleEntry<>(
                            data.getId(),
                            uri
                        ));
                    } else {
                        return Optional.of(new AbstractMap.SimpleEntry<>(
                            data.getId(),
                            storageInterface.from(execution, data.getId(), new File(((String) current)))
                        ));
                    }
                } catch (Exception e) {
                    throw new MissingRequiredArgument("Invalid " + loggableType + " arguments for file on " + loggableType + " '" + data.getId() + "'", e);
                }
            }
            case JSON -> {
                try {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        JacksonMapper.toObject(((String) current))
                    ));
                } catch (JsonProcessingException e) {
                    throw new MissingRequiredArgument("Invalid JSON format for '" + data.getId() + "' for '" + current + "' with error " + e.getMessage(), e);
                }
            }
            case URI -> {
                Matcher matcher = URI_PATTERN.matcher((String) current);
                if (matcher.matches()) {
                    return Optional.of(new AbstractMap.SimpleEntry<>(
                        data.getId(),
                        current
                    ));
                } else {
                    throw new MissingRequiredArgument("Invalid URI format for '" + data.getId() + "' for '" + current + "'");
                }
            }
            default ->
                throw new MissingRequiredArgument("Invalid data type '" + data.getType() + "' for '" + data.getId() + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleNestedInputs(Map<String, Object> inputs) {
        Map<String, Object> result = new TreeMap<>();

        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            String[] f = entry.getKey().split("\\.");
            Map<String, Object> t = result;
            int i = 0;
            for (int m = f.length - 1; i < m; ++i) {
                t = (Map<String, Object>) t.computeIfAbsent(f[i], k -> new TreeMap<>());
            }

            t.put(f[i], entry.getValue());
        }

        return result;
    }

    public Execution runOne(String tenantId, String namespace, String flowId) throws TimeoutException {
        return this.runOne(tenantId, namespace, flowId, null, null, null, null);
    }

    public Execution runOne(String tenantId, String namespace, String flowId, Integer revision) throws TimeoutException {
        return this.runOne(tenantId, namespace, flowId, revision, null, null, null);
    }

    public Execution runOne(String tenantId, String namespace, String flowId, Integer revision, BiFunction<Flow, Execution, Map<String, Object>> inputs) throws TimeoutException {
        return this.runOne(tenantId, namespace, flowId, revision, inputs, null, null);
    }

    public Execution runOne(String tenantId, String namespace, String flowId, Duration duration) throws TimeoutException {
        return this.runOne(tenantId, namespace, flowId, null, null, duration, null);
    }

    public Execution runOne(String tenantId, String namespace, String flowId, Integer revision, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        return this.runOne(tenantId, namespace, flowId, revision, inputs, duration, null);
    }

    public Execution runOne(String tenantId, String namespace, String flowId, Integer revision, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration, List<Label> labels) throws TimeoutException {
        return this.runOne(
            flowRepository
                .findById(tenantId, namespace, flowId, revision != null ? Optional.of(revision) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("Unable to find flow '" + flowId + "'")),
            inputs,
            duration,
            labels);
    }

    public Execution runOne(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs) throws TimeoutException {
        return this.runOne(flow, inputs, null, null);
    }

    public Execution runOne(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        return this.runOne(flow, inputs, duration, null);
    }

    public Execution runOne(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration, List<Label> labels) throws TimeoutException {
        if (duration == null) {
            duration = Duration.ofSeconds(15);
        }

        Execution execution = this.newExecution(flow, inputs, labels);

        return this.awaitExecution(isTerminatedExecution(execution, flow), () -> {
            this.executionQueue.emit(execution);
        }, duration);
    }

    public Execution runOneUntilPaused(String tenantId, String namespace, String flowId) throws TimeoutException {
        return this.runOneUntilPaused(tenantId, namespace, flowId, null, null, null);
    }

    public Execution runOneUntilPaused(String tenantId, String namespace, String flowId, Integer revision, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        return this.runOneUntilPaused(
            flowRepository
                .findById(tenantId, namespace, flowId, revision != null ? Optional.of(revision) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("Unable to find flow '" + flowId + "'")),
            inputs,
            duration
        );
    }

    public Execution runOneUntilPaused(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        if (duration == null) {
            duration = Duration.ofSeconds(15);
        }

        Execution execution = this.newExecution(flow, inputs, null);

        return this.awaitExecution(isPausedExecution(execution), () -> {
            this.executionQueue.emit(execution);
        }, duration);
    }

    public Execution runOneUntilRunning(String tenantId, String namespace, String flowId) throws TimeoutException {
        return this.runOneUntilRunning(tenantId, namespace, flowId, null, null, null);
    }

    public Execution runOneUntilRunning(String tenantId, String namespace, String flowId, Integer revision, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        return this.runOneUntilRunning(
            flowRepository
                .findById(tenantId, namespace, flowId, revision != null ? Optional.of(revision) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("Unable to find flow '" + flowId + "'")),
            inputs,
            duration
        );
    }

    public Execution runOneUntilRunning(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs, Duration duration) throws TimeoutException {
        if (duration == null) {
            duration = Duration.ofSeconds(15);
        }

        Execution execution = this.newExecution(flow, inputs, null);

        return this.awaitExecution(isRunningExecution(execution), () -> {
            this.executionQueue.emit(execution);
        }, duration);
    }

    public Execution awaitExecution(Predicate<Execution> predicate, Runnable executionEmitter, Duration duration) throws TimeoutException {
        AtomicReference<Execution> receive = new AtomicReference<>();

        Runnable cancel = this.executionQueue.receive(current -> {
            if (predicate.test(current.getLeft())) {
                receive.set(current.getLeft());
            }
        });

        executionEmitter.run();

        if (duration == null) {
            Await.until(() -> receive.get() != null, Duration.ofMillis(10));
        } else {
            Await.until(() -> receive.get() != null, Duration.ofMillis(10), duration);
        }

        cancel.run();

        return receive.get();
    }

    public Execution awaitChildExecution(Flow flow, Execution parentExecution, Runnable executionEmitter, Duration duration) throws TimeoutException {
        return this.awaitExecution(isTerminatedChildExecution(parentExecution, flow), executionEmitter, duration);
    }

    public Execution awaitExecution(Flow flow, Execution execution, Runnable executionEmitter, Duration duration) throws TimeoutException {
        return this.awaitExecution(isTerminatedExecution(execution, flow), executionEmitter, duration);
    }

    public Predicate<Execution> isTerminatedExecution(Execution execution, Flow flow) {
        return e -> e.getId().equals(execution.getId()) && conditionService.isTerminatedWithListeners(flow, e);
    }

    public Predicate<Execution> isPausedExecution(Execution execution) {
        return e -> e.getId().equals(execution.getId()) && e.getState().isPaused() && e.getTaskRunList() != null && e.getTaskRunList().stream().anyMatch(t -> t.getState().isPaused());
    }

    public Predicate<Execution> isRunningExecution(Execution execution) {
        return e -> e.getId().equals(execution.getId()) && e.getState().isRunning() && e.getTaskRunList() != null && e.getTaskRunList().stream().anyMatch(t -> t.getState().isRunning());
    }

    private Predicate<Execution> isTerminatedChildExecution(Execution parentExecution, Flow flow) {
        return e -> e.getParentId() != null && e.getParentId().equals(parentExecution.getId()) && conditionService.isTerminatedWithListeners(flow, e);
    }

    public Execution newExecution(Flow flow, BiFunction<Flow, Execution, Map<String, Object>> inputs, List<Label> labels) {
        Execution execution = Execution.builder()
            .id(IdUtils.create())
            .tenantId(flow.getTenantId())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .flowRevision(flow.getRevision())
            .state(new State())
            .build();

        if (inputs != null) {
            execution = execution.withInputs(inputs.apply(flow, execution));
        }

        List<Label> executionLabels = new ArrayList<>();
        if (flow.getLabels() != null) {
            executionLabels.addAll(flow.getLabels());
        }
        if (labels != null) {
            executionLabels.addAll(labels);
        }
        if (!executionLabels.isEmpty()) {
            execution = execution.withLabels(executionLabels);
        }

        return execution;
    }
}
