package org.rakam.analysis;

import com.google.inject.Inject;
import org.rakam.collection.event.metastore.Metastore;
import org.rakam.plugin.AbstractUserService;
import org.rakam.plugin.UserStorage;
import org.rakam.report.PrestoConfig;
import org.rakam.report.PrestoQueryExecutor;
import org.rakam.util.JsonHelper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.rakam.util.ValidationUtil.checkProject;

/**
 * Created by buremba <Burak Emre Kabakcı> on 29/04/15 20:26.
 */
public class PrestoAbstractUserService extends AbstractUserService {
    private final Metastore metastore;
    private final PrestoConfig prestoConfig;
    private final PrestoQueryExecutor executor;

    @Inject
    public PrestoAbstractUserService(UserStorage storage, Metastore metastore, PrestoConfig prestoConfig, PrestoQueryExecutor executor) {
        super(storage);
        this.metastore = metastore;
        this.prestoConfig = prestoConfig;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<List<CollectionEvent>> getEvents(String project, String user, int limit, long offset) {
        checkProject(project);
        checkNotNull(user);
        checkArgument(limit <= 1000, "Maximum 1000 events can be fetched at once.");
        String sqlQuery = metastore.getCollections(project).entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(field -> field.getName().equals("user")))
                .filter(entry -> entry.getValue().stream().anyMatch(field -> field.getName().equals("time")))
                .map(entry ->
                        format("select '%s' as collection, '{", entry.getKey()) + entry.getValue().stream()
                                .filter(field -> !field.getName().equals("user"))
                                .map(field -> {
                                    switch (field.getType()) {
                                        case LONG:
                                        case DOUBLE:
                                        case BOOLEAN:
                                            return format("\"%1$s\": '||COALESCE(cast(%1$s as varchar), 'null')||'", field.getName());
                                        default:
                                            return format("\"%1$s\": \"'||COALESCE(replace(try_cast(%1$s as varchar), '\n', '\\n'), 'null')||'\"", field.getName());
                                    }
                                })
                                .collect(Collectors.joining(", ")) +
                                format(" }' as json, time from %s where user = %s",
                                        prestoConfig.getColdStorageConnector() + "." + project + "." + entry.getKey(),
                                        user))
                .collect(Collectors.joining(" union all "));
        return executor.executeRawQuery(format("select json from (%s) order by time desc limit %d offset %s", sqlQuery, limit, offset)).getResult()
                .thenApply(result -> {
                    Object collect = result.getResult().stream()
                            .map(s -> new CollectionEvent((String) s.get(0), JsonHelper.read(s.get(1).toString(), Map.class)))
                            .collect(Collectors.toList());
                    return (List<CollectionEvent>) collect;
                });
    }
}