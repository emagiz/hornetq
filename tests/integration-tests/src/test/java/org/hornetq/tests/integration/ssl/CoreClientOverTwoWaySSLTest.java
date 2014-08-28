/*
 * Copyright 2005-2014 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.tests.integration.ssl;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.netty.handler.ssl.SslHandler;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.HornetQNotConnectedException;
import org.hornetq.api.core.Interceptor;
import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.protocol.core.Packet;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.core.remoting.impl.netty.NettyConnection;
import org.hornetq.core.remoting.impl.netty.TransportConstants;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.tests.util.RandomUtil;
import org.hornetq.tests.util.ServiceTestBase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author Justin Bertram
 */
@RunWith(value = Parameterized.class)
public class CoreClientOverTwoWaySSLTest extends ServiceTestBase
{
   @Parameterized.Parameters(name = "storeType={0}")
   public static Collection getParameters()
   {
      return Arrays.asList(new Object[][]{
         {"JCEKS"},
         {"JKS"}
      });
   }

   public CoreClientOverTwoWaySSLTest(String storeType)
   {
      this.storeType = storeType;
      SERVER_SIDE_KEYSTORE = "server-side-keystore." + storeType.toLowerCase();
      SERVER_SIDE_TRUSTSTORE = "server-side-truststore." + storeType.toLowerCase();
      CLIENT_SIDE_TRUSTSTORE = "client-side-truststore." + storeType.toLowerCase();
      CLIENT_SIDE_KEYSTORE = "client-side-keystore." + storeType.toLowerCase();
   }

   public static final SimpleString QUEUE = new SimpleString("QueueOverSSL");

   /** These artifacts are required for testing 2-way SSL
    *
    * Commands to create the JKS artifacts:
    * keytool -genkey -keystore client-side-keystore.jks -storepass secureexample -keypass secureexample -dname "CN=HornetQ, OU=HornetQ, O=HornetQ, L=HornetQ, S=HornetQ, C=HQ"
    * keytool -export -keystore client-side-keystore.jks -file hornetq-jks.cer -storepass secureexample
    * keytool -import -keystore server-side-truststore.jks -file hornetq-jks.cer -storepass secureexample -keypass secureexample -noprompt
    *
    * Commands to create the JCEKS artifacts:
    * keytool -genkey -keystore client-side-keystore.jceks -storetype JCEKS -storepass secureexample -keypass secureexample -dname "CN=HornetQ, OU=HornetQ, O=HornetQ, L=HornetQ, S=HornetQ, C=HQ"
    * keytool -export -keystore client-side-keystore.jceks -file hornetq-jceks.cer -storetype jceks -storepass secureexample
    * keytool -import -keystore server-side-truststore.jceks -storetype JCEKS -file hornetq-jceks.cer -storepass secureexample -keypass secureexample -noprompt
    */

   private static String storeType;
   private static String SERVER_SIDE_KEYSTORE;
   private static String SERVER_SIDE_TRUSTSTORE;
   private static String CLIENT_SIDE_TRUSTSTORE;
   private static String CLIENT_SIDE_KEYSTORE;
   private static final String PASSWORD = "secureexample";

   private HornetQServer server;

   private TransportConfiguration tc;

   private class MyInterceptor implements Interceptor
   {
      public boolean intercept(final Packet packet, final RemotingConnection connection) throws HornetQException
      {
         if (packet.getType() == PacketImpl.SESS_SEND)
         {
            try
            {
               if (connection.getTransportConnection() instanceof NettyConnection)
               {
                  System.out.println("Passed through....");
                  NettyConnection nettyConnection = (NettyConnection) connection.getTransportConnection();
                  SslHandler sslHandler = (SslHandler) nettyConnection.getChannel().pipeline().get("ssl");
                  assertNotNull(sslHandler);
                  assertNotNull(sslHandler.engine().getSession());
                  assertNotNull(sslHandler.engine().getSession().getPeerCertificateChain());
               }
            }
            catch (SSLPeerUnverifiedException e)
            {
               Assert.fail(e.getMessage());
            }
         }
         return true;
      }
   }

   @Test
   public void testTwoWaySSL() throws Exception
   {
      String text = RandomUtil.randomString();

      tc.getParams().put(TransportConstants.SSL_ENABLED_PROP_NAME, true);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME, storeType);
      tc.getParams().put(TransportConstants.KEYSTORE_PROVIDER_PROP_NAME, storeType);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PATH_PROP_NAME, CLIENT_SIDE_TRUSTSTORE);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME, PASSWORD);
      tc.getParams().put(TransportConstants.KEYSTORE_PATH_PROP_NAME, CLIENT_SIDE_KEYSTORE);
      tc.getParams().put(TransportConstants.KEYSTORE_PASSWORD_PROP_NAME, PASSWORD);

      server.getRemotingService().addIncomingInterceptor(new MyInterceptor());

      ServerLocator locator = addServerLocator(HornetQClient.createServerLocatorWithoutHA(tc));
      ClientSessionFactory sf = createSessionFactory(locator);
      ClientSession session = sf.createSession(false, true, true);
      session.createQueue(CoreClientOverTwoWaySSLTest.QUEUE, CoreClientOverTwoWaySSLTest.QUEUE, false);
      ClientProducer producer = session.createProducer(CoreClientOverTwoWaySSLTest.QUEUE);

      ClientMessage message = createTextMessage(session, text);
      producer.send(message);

      ClientConsumer consumer = session.createConsumer(CoreClientOverTwoWaySSLTest.QUEUE);
      session.start();

      Message m = consumer.receive(1000);
      Assert.assertNotNull(m);
      Assert.assertEquals(text, m.getBodyBuffer().readString());
   }

   @Test
   public void testTwoWaySSLWithoutClientKeyStore() throws Exception
   {
      tc.getParams().put(TransportConstants.SSL_ENABLED_PROP_NAME, true);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME, storeType);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PATH_PROP_NAME, CLIENT_SIDE_TRUSTSTORE);
      tc.getParams().put(TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME, PASSWORD);

      ServerLocator locator = addServerLocator(HornetQClient.createServerLocatorWithoutHA(tc));
      try
      {
         createSessionFactory(locator);
         Assert.fail();
      }
      catch (HornetQNotConnectedException se)
      {
         //ok
      }
      catch (HornetQException e)
      {
         fail("Invalid Exception type:" + e.getType());
      }
   }

   // Package protected ---------------------------------------------

   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();
      ConfigurationImpl config = createBasicConfig();
      config.setSecurityEnabled(false);
      Map<String, Object> params = new HashMap<String, Object>();
      params.put(TransportConstants.SSL_ENABLED_PROP_NAME, true);
      params.put(TransportConstants.KEYSTORE_PATH_PROP_NAME, SERVER_SIDE_KEYSTORE);
      params.put(TransportConstants.KEYSTORE_PASSWORD_PROP_NAME, PASSWORD);
      params.put(TransportConstants.TRUSTSTORE_PATH_PROP_NAME, SERVER_SIDE_TRUSTSTORE);
      params.put(TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME, PASSWORD);
      params.put(TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME, storeType);
      params.put(TransportConstants.KEYSTORE_PROVIDER_PROP_NAME, storeType);
      params.put(TransportConstants.NEED_CLIENT_AUTH_PROP_NAME, true);
      config.getAcceptorConfigurations().add(new TransportConfiguration(NETTY_ACCEPTOR_FACTORY, params));
      server = createServer(false, config);
      server.start();
      waitForServer(server);
      tc = new TransportConfiguration(NETTY_CONNECTOR_FACTORY);
   }
}
