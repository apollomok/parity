/*
 * Copyright 2014 Parity authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.paritytrading.parity.fix;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;

class Events {

    private static final int TIMEOUT = 500;

    static void process(FIXAcceptor fix) throws IOException {
        Selector selector = Selector.open();

        final List<Session> toKeepAlive = new ArrayList<>();
        final List<Session> toCleanUp   = new ArrayList<>();

        fix.getServerChannel().register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int numKeys = selector.select(TIMEOUT);

            if (numKeys > 0) {
                for (SelectionKey key : selector.selectedKeys()) {
                    if (key.isAcceptable()) {
                        final Session session = fix.accept();
                        if (session == null)
                            continue;

                        session.getFIX().getChannel().register(selector,
                                SelectionKey.OP_READ, new Receiver() {

                                    @Override
                                    public void close() {
                                        toCleanUp.add(session);
                                    }

                                    @Override
                                    public int receive() throws IOException {
                                        return session.getFIX().receive();
                                    }

                                });

                        session.getOrderEntry().getChannel().register(selector,
                                SelectionKey.OP_READ, new Receiver() {

                                    @Override
                                    public void close() {
                                        toCleanUp.add(session);
                                    }

                                    @Override
                                    public int receive() throws IOException {
                                        return session.getOrderEntry().receive();
                                    }

                                });

                        toKeepAlive.add(session);
                    } else {
                        Receiver receiver = (Receiver)key.attachment();

                        try {
                            if (receiver.receive() < 0)
                                receiver.close();
                        } catch (IOException e1) {
                            try {
                                receiver.close();
                            } catch (IOException e2) {
                            }
                        }
                    }
                }

                selector.selectedKeys().clear();
            }

            for (int i = 0; i < toKeepAlive.size(); i++) {
                Session session = toKeepAlive.get(i);

                session.getFIX().updateCurrentTimestamp();

                try {
                    session.getOrderEntry().keepAlive();
                    session.getFIX().keepAlive();
                } catch (IOException e) {
                    toCleanUp.add(session);
                }
            }

            if (toCleanUp.isEmpty())
                continue;

            for (int i = 0; i < toCleanUp.size(); i++) {
                try (Session session = toCleanUp.get(i)) {
                    toKeepAlive.remove(session);
                }
            }

            toCleanUp.clear();
        }
    }

    private interface Receiver extends Closeable {

        int receive() throws IOException;

    }

}
