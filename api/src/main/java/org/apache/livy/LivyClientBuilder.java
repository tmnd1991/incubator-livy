/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A builder for Livy clients.
 */
public final class LivyClientBuilder {

  public static final String LIVY_URI_KEY = "livy.uri";
  public static final String LIVY_SESSION_ID_KEY = "livy.sessionId";

  private static final ServiceLoader<LivyClientFactory> CLIENT_FACTORY_LOADER =
    ServiceLoader.load(LivyClientFactory.class, classLoader());

  private static List<LivyClientFactory> getLivyClientFactories() {
    List<LivyClientFactory> factories = new ArrayList<>();
    for (LivyClientFactory f : CLIENT_FACTORY_LOADER) {
      factories.add(f);
    }
    return factories;
  }

  private static final List<LivyClientFactory> CLIENT_FACTORIES = getLivyClientFactories();

  private final Properties config;

  /**
   * Creates a new builder that will automatically load the default Livy and Spark configuration
   * from the classpath.
   *
   * @throws IOException If an error occurred when reading from the config files.
   */
  public LivyClientBuilder() throws IOException {
    this(true);
  }

  /**
   * Creates a new builder that will optionally load the default Livy and Spark configuration
   * from the classpath.
   *
   * Livy client configuration is stored in a file called "livy-client.conf", and Spark client
   * configuration is stored in a file called "spark-defaults.conf", both in the root of the
   * application's classpath. Livy configuration takes precedence over Spark's (in case
   * configuration entries are duplicated), and configuration set in this builder object will
   * override the values in those files.
   *
   * @param loadDefaults Whether to load configs from spark-defaults.conf and livy-client.conf
   *                     if they are found in the application's classpath.
   * @throws IOException If an error occurred when reading from the config files.
   */
  public LivyClientBuilder(boolean loadDefaults) throws IOException {
    this.config = new Properties();

    if (loadDefaults) {
      String[] confFiles = { "spark-defaults.conf", "livy-client.conf" };

      for (String file : confFiles) {
        URL url = classLoader().getResource(file);
        if (url != null) {
          Reader r = new InputStreamReader(url.openStream(), UTF_8);
          try {
            config.load(r);
          } finally {
            r.close();
          }
        }
      }
    }
  }

  /**
   * Set the URI of the Livy server the client will connect to.
   * If the URI contains <pre>sessions/{sessionId}</pre>,
   * the client will connect to the specified existing session;
   * otherwise it will create a new session.
   *
   * @param uri The URI of Livy server.
   * @return The builder itself.
   */
  public LivyClientBuilder setURI(URI uri) {
    config.setProperty(LIVY_URI_KEY, uri.toString());
    return this;
  }

  /**
   * Sets the sessionId the client will connect to. If sessionId is set,
   * all Spark configurations will be ignored and the original session ones will be used.
   * If not set, a new session will be created when the client is built.
   *
   * @param sessionId The ID of the session to attach to.
   * @return the builder itself.
   */
  public LivyClientBuilder setSessionId(int sessionId) {
    config.setProperty(LIVY_SESSION_ID_KEY, String.valueOf(sessionId));
    return this;
  }

  public LivyClientBuilder setConf(String key, String value) {
    if (value != null) {
      config.setProperty(key, value);
    } else {
      config.remove(key);
    }
    return this;
  }

  public LivyClientBuilder setAll(Map<String, String> props) {
    config.putAll(props);
    return this;
  }

  public LivyClientBuilder setAll(Properties props) {
    config.putAll(props);
    return this;
  }

  public LivyClient build() {
    String uriStr = config.getProperty(LIVY_URI_KEY);
    if (uriStr == null) {
      throw new IllegalArgumentException("URI must be provided.");
    }
    URI uri;
    try {
      uri = new URI(uriStr);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid URI.", e);
    }

    LivyClient client = null;
    if (CLIENT_FACTORIES.isEmpty()) {
      throw new IllegalStateException("No LivyClientFactory implementation was found.");
    }

    for (LivyClientFactory factory : CLIENT_FACTORIES) {
      try {
        client = factory.createClient(uri, config);
      } catch (Exception e) {
        if (!(e instanceof RuntimeException)) {
          e = new RuntimeException(e);
        }
        throw (RuntimeException) e;
      }
      if (client != null) {
        break;
      }
    }

    if (client == null) {
      // Redact any user information from the URI when throwing user-visible exceptions that might
      // be logged.
      if (uri.getUserInfo() != null) {
        try {
          uri = new URI(uri.getScheme(), "[redacted]", uri.getHost(), uri.getPort(), uri.getPath(),
            uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
          // Shouldn't really happen.
          throw new RuntimeException(e);
        }
      }

      throw new IllegalArgumentException(String.format(
        "URI '%s' is not supported by any registered client factories.", uri));
    }
    return client;
  }

  private static ClassLoader classLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = LivyClientBuilder.class.getClassLoader();
    }
    return cl;
  }

}
