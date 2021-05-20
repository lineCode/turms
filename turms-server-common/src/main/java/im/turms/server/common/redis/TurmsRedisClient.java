/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.turms.server.common.redis;

import im.turms.server.common.redis.codec.TurmsRedisCodecAdapter;
import im.turms.server.common.redis.codec.context.RedisCodecContext;
import im.turms.server.common.redis.command.TurmsCommandEncoder;
import im.turms.server.common.redis.script.RedisScript;
import io.lettuce.core.AbstractRedisReactiveCommands;
import io.lettuce.core.GeoArgs;
import io.lettuce.core.GeoCoordinates;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisReactiveCommandsImpl;
import io.lettuce.core.TurmsRedisCommandBuilder;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.CommandEncoder;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DefaultEventLoopGroupProvider;
import io.lettuce.core.resource.NettyCustomizer;
import io.lettuce.core.resource.ThreadFactoryProvider;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Data;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.geo.Point;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.AbstractMap;
import java.util.Map;

import static io.lettuce.core.protocol.CommandType.GEORADIUSBYMEMBER;

/**
 * @author James Chen
 * @see AbstractRedisReactiveCommands
 */
@Log4j2
@Data
public class TurmsRedisClient {

    private final DefaultClientResources resources;

    private final RedisClient nativeClient;

    private final StatefulRedisConnection<ByteBuf, ByteBuf> nativeConnection;

    private final RedisReactiveCommandsImpl<ByteBuf, ByteBuf> commands;

    private final TurmsRedisCommandBuilder commandBuilder;

    @Setter
    private RedisCodecContext serializationContext;

    public TurmsRedisClient(String uri,
                            RedisCodecContext serializationContext) {
        this.serializationContext = serializationContext;
        commandBuilder = new TurmsRedisCommandBuilder(serializationContext);
        DefaultEventExecutorGroup eventExecutorGroup = new DefaultEventExecutorGroup(DefaultClientResources.MIN_COMPUTATION_THREADS,
                new DefaultThreadFactory("lettuce-eventExecutorLoop"));
        DefaultEventLoopGroupProvider eventLoopGroupProvider =
                new DefaultEventLoopGroupProvider(DefaultClientResources.MIN_IO_THREADS,
                        (ThreadFactoryProvider) poolName -> new DefaultThreadFactory(poolName, true));
        resources = DefaultClientResources
                .builder()
                // For event bus: io.lettuce.core.event.DefaultEventBus
                .eventExecutorGroup(eventExecutorGroup)
                .eventLoopGroupProvider(eventLoopGroupProvider)
                .nettyCustomizer(new NettyCustomizer() {
                    /**
                     * ConnectionBuilder$PlainChannelInitializer
                     * ChannelGroupListener
                     * CommandEncoder
                     * RedisHandshakeHandler
                     * CommandHandler
                     * ConnectionEventTrigger
                     * ConnectionWatchdog
                     * @see io.lettuce.core.SslConnectionBuilder.SslChannelInitializer
                     * @see io.lettuce.core.SslConnectionBuilder.PlainChannelInitializer
                     */
                    @Override
                    public void afterChannelInitialized(Channel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.replace(CommandEncoder.class, null, new TurmsCommandEncoder());
                    }
                })
                .build();
        nativeClient = RedisClient.create(resources, uri);
        nativeConnection = nativeClient.connect(TurmsRedisCodecAdapter.DEFAULT);
        commands = (RedisReactiveCommandsImpl<ByteBuf, ByteBuf>) nativeConnection.reactive();
    }

    public void destroy() {
        nativeClient.shutdown();
    }

    // Hashes

    public Mono<Long> hdel(Object key, Object... fields) {
        ByteBuf keyBuffer = serializationContext.encodeHashKey(key);
        ByteBuf[] fieldBuffers = serializationContext.encodeHashFields(fields);
        return commands.hdel(keyBuffer, fieldBuffers);
    }

    public <K, V> Flux<Map.Entry<K, V>> hgetall(K key) {
        ByteBuf keyBuffer = serializationContext.encodeHashKey(key);
        Flux<KeyValue<K, V>> flux = commands.createDissolvingFlux(() -> commandBuilder.hgetall(keyBuffer));
        return flux
                .flatMap(entry -> {
                    if (entry.isEmpty()) {
                        return Mono.empty();
                    }
                    return Mono.just(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
                });
    }

    // Geo

    public Mono<Long> geoadd(Object key, Point coordinates, Object member) {
        ByteBuf keyBuffer = serializationContext.encodeGeoKey(key);
        ByteBuf memberBuffer = serializationContext.encodeGeoMember(member);
        return commands.geoadd(keyBuffer, coordinates.getX(), coordinates.getY(), memberBuffer);
    }

    public Flux<GeoCoordinates> geopos(Object key, Object... members) {
        ByteBuf keyBuffer = serializationContext.encodeGeoKey(key);
        ByteBuf[] memberBuffers = serializationContext.encodeGeoMembers(members);
        return commands.geopos(keyBuffer, memberBuffers)
                .flatMap(value -> value.isEmpty() ? Mono.empty() : Mono.just(value.getValue()));
    }

    public <T> Flux<T> georadiusbymember(Object key, Object member, double distance, GeoArgs geoArgs) {
        ByteBuf keyBuffer = serializationContext.encodeGeoKey(key);
        ByteBuf memberBuffer = serializationContext.encodeGeoMember(member);
        return commands.createDissolvingFlux(() -> commandBuilder
                .georadiusbymember(GEORADIUSBYMEMBER, keyBuffer, memberBuffer, distance, GeoArgs.Unit.m.name(), geoArgs));
    }

    public Mono<Long> georem(Object key, Object... members) {
        ByteBuf keyBuffer = serializationContext.encodeGeoKey(key);
        ByteBuf[] memberBuffers = serializationContext.encodeGeoMembers(members);
        return commands.zrem(keyBuffer, memberBuffers);
    }

    // Scripting

    public <T> Mono<T> eval(RedisScript script, ByteBuf... keys) {
        for (ByteBuf key : keys) {
            key.retain();
        }
        return (Mono<T>) commands
                .createFlux(() -> commandBuilder.evalsha(script.getDigest(), script.getOutputType(), keys))
                .onErrorResume(e -> {
                    if (exceptionContainsNoScriptException(e)) {
                        return commands.createFlux(
                                () -> commandBuilder.eval(script.getScript(), script.getOutputType(), keys));
                    }
                    return Flux.error(e);
                })
                .doFinally(type -> {
                    for (ByteBuf key : keys) {
                        if (key.refCnt() > 0) {
                            key.release();
                        }
                    }
                })
                .single();
    }

    private static boolean exceptionContainsNoScriptException(Throwable e) {
        if (e instanceof RedisNoScriptException) {
            return true;
        }
        Throwable current = e.getCause();
        while (current != null) {
            if (current instanceof RedisNoScriptException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}