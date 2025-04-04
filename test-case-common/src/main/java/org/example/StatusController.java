/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.example;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.netty.channel.loom.PrivateLoomSupport;
import io.micronaut.http.server.netty.NettyHttpRequest;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import jakarta.inject.Inject;

@Controller("/status")
public class StatusController {
    @Inject
    JsonMapper jsonMapper;

    @Get
    public Status getStatus(HttpRequest<?> request) {
        Channel ch = ((NettyHttpRequest<?>) request).getChannelHandlerContext().channel();
        while (true) {
            Channel parent = ch.parent();
            if (parent == null) {
                break;
            }
            ch = parent;
        }
        return new Status(
                ch.toString(),
                SslContext.defaultServerProvider(),
                jsonMapper.getClass().getName(),
                Thread.currentThread().getName(),
                Thread.currentThread().isVirtual() ? PrivateLoomSupport.getCarrierThread(Thread.currentThread()).getName() : null
        );
    }

    @Serdeable
    record Status(String serverSocketChannelImplementation,
                  SslProvider sslProvider,
                  String jsonMapperImplementation,
                  String threadName,
                  String carrierName) {}
}
