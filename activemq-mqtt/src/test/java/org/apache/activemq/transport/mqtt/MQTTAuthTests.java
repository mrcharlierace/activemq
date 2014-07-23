/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.net.ProtocolException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.fusesource.mqtt.client.Tracer;
import org.fusesource.mqtt.codec.CONNACK;
import org.fusesource.mqtt.codec.MQTTFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests various use cases that require authentication or authorization over MQTT
 */
@RunWith(Parameterized.class)
public class MQTTAuthTests extends MQTTAuthTestSupport {

    private static final Logger LOG = LoggerFactory.getLogger(MQTTAuthTests.class);

    @Parameters(name= "{index}: scheme({0})")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {"mqtt", false},
                {"mqtt+ssl", true},
                {"mqtt+nio", false}
                // TODO - Fails {"mqtt+nio+ssl", true}
            });
    }

    @Test(timeout = 60 * 1000)
    public void testAnonymousUserConnect() throws Exception {
        MQTT mqtt = createMQTTConnection();
        mqtt.setCleanSession(true);
        mqtt.setUserName((String)null);
        mqtt.setPassword((String)null);
        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();
        LOG.info("Connected as anonymous client");
        connection.disconnect();
    }

    @Test(timeout = 60 * 1000)
    public void testBadUserNameOrPasswordGetsConnAckWithErrorCode() throws Exception {
        MQTT mqttPub = createMQTTConnection("pub", true);
        mqttPub.setUserName("foo");
        mqttPub.setPassword("bar");

        final AtomicBoolean failed = new AtomicBoolean();

        mqttPub.setTracer(new Tracer() {
            @Override
            public void onReceive(MQTTFrame frame) {
                LOG.info("Client received: {}", frame);
                if (frame.messageType() == CONNACK.TYPE) {
                    CONNACK connAck = new CONNACK();
                    try {
                        connAck.decode(frame);
                        LOG.info("{}", connAck);
                        assertEquals(CONNACK.Code.CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD, connAck.code());
                    } catch (ProtocolException e) {
                        failed.set(true);
                        fail("Error decoding publish " + e.getMessage());
                    } catch (Throwable err) {
                        failed.set(true);
                        throw err;
                    }
                }
            }

            @Override
            public void onSend(MQTTFrame frame) {
                LOG.info("Client sent: {}", frame);
            }
        });

        BlockingConnection connectionPub = mqttPub.blockingConnection();
        try {
            connectionPub.connect();
            fail("Should not be able to connect.");
        } catch (Exception e) {
        }

        assertFalse("connection should have failed.", failed.get());
    }

    @Test(timeout = 60 * 1000)
    public void testFailedSubscription() throws Exception {
        final String ANONYMOUS = "anonymous";

        MQTT mqtt = createMQTTConnection();
        mqtt.setClientId("foo");
        mqtt.setKeepAlive((short) 2);

        final BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        final String NAMED = "named";
        byte[] qos = connection.subscribe(new Topic[] { new Topic(NAMED, QoS.AT_MOST_ONCE), new Topic(ANONYMOUS, QoS.EXACTLY_ONCE) });
        assertEquals((byte) 0x80, qos[0]);
        assertEquals((byte) QoS.EXACTLY_ONCE.ordinal(), qos[1]);

        // validate the subscription by sending a retained message
        connection.publish(ANONYMOUS, ANONYMOUS.getBytes(), QoS.AT_MOST_ONCE, true);
        Message msg = connection.receive(1000, TimeUnit.MILLISECONDS);
        assertNotNull(msg);
        assertEquals(ANONYMOUS, new String(msg.getPayload()));
        msg.ack();

        connection.unsubscribe(new String[] { ANONYMOUS });
        qos = connection.subscribe(new Topic[] { new Topic(ANONYMOUS, QoS.AT_LEAST_ONCE) });
        assertEquals((byte) QoS.AT_LEAST_ONCE.ordinal(), qos[0]);

        msg = connection.receive(1000, TimeUnit.MILLISECONDS);
        assertNotNull(msg);
        assertEquals(ANONYMOUS, new String(msg.getPayload()));
        msg.ack();

        connection.disconnect();
    }

    @Test(timeout = 60 * 1000)
    public void testWildcardRetainedSubscription() throws Exception {
        MQTT mqttPub = createMQTTConnection("pub", true);
        mqttPub.setUserName("admin");
        mqttPub.setPassword("admin");

        BlockingConnection connectionPub = mqttPub.blockingConnection();
        connectionPub.connect();
        connectionPub.publish("one", "test".getBytes(), QoS.AT_LEAST_ONCE, true);

        MQTT mqttSub = createMQTTConnection("sub", true);
        mqttSub.setUserName("user");
        mqttSub.setPassword("password");
        BlockingConnection connectionSub = mqttSub.blockingConnection();
        connectionSub.connect();
        connectionSub.subscribe(new Topic[]{new Topic("#", QoS.AT_LEAST_ONCE)});
        Message msg = connectionSub.receive(1, TimeUnit.SECONDS);
        assertNull("Shouldn't receive the message", msg);
    }
}