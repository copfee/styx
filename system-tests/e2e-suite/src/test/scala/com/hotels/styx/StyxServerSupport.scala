/**
 * Copyright (C) 2013-2017 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hotels.styx

import java.net.InetSocketAddress
import java.util
import java.util.Collections._
import java.util.concurrent.TimeUnit.MILLISECONDS

import com.hotels.styx.admin.AdminServerConfig
import com.hotels.styx.api.HttpInterceptor.Chain
import com.hotels.styx.api.configuration.Configuration.MapBackedConfiguration
import com.hotels.styx.api.plugins.spi.Plugin
import com.hotels.styx.api.{HttpHandler, HttpRequest, HttpResponse}
import com.hotels.styx.client.applications.BackendService
import com.hotels.styx.infrastructure.Registry
import com.hotels.styx.infrastructure.configuration.yaml.YamlConfig
import com.hotels.styx.metrics.StyxMetrics
import com.hotels.styx.proxy.ProxyServerConfig
import com.hotels.styx.proxy.plugin.NamedPlugin
import com.hotels.styx.server.netty.NettyServerConfig.Connectors
import com.hotels.styx.server.{HttpConnectorConfig, HttpsConnectorConfig}
import com.hotels.styx.support.CodaHaleMetricsFacade
import rx.Observable

import scala.collection.JavaConverters._

object StyxServerSupport {
  def newHttpConnConfig(port: Int): HttpConnectorConfig = new HttpConnectorConfig(port)

  def newHttpsConnConfig(port: Int): HttpsConnectorConfig = new HttpsConnectorConfig.Builder()
    .sslProvider("JDK")
    .port(port)
    .build()

  def newHttpsConnConfig(conf: HttpsConnectorConfig): HttpsConnectorConfig = {
    val builder = new HttpsConnectorConfig.Builder()
      .sslProvider(conf.sslProvider)
      .port(conf.port())
      .sessionCacheSize(conf.sessionCacheSize)
      .sessionTimeout(conf.sessionTimeoutMillis, MILLISECONDS)

    if (conf.certificateFile != null) {
      builder.certificateFile(conf.certificateFile)
    }
    if (conf.certificateKeyFile != null) {
      builder.certificateKeyFile(conf.certificateKeyFile)
    }

    builder.build()
  }

  def newAdminServerConfigBuilder(adminHttpConnConfig: HttpConnectorConfig) = {
    new AdminServerConfig.Builder()
      .setHttpConnector(adminHttpConnConfig)
  }

  def newProxyServerConfigBuilder(httpConnConfig: HttpConnectorConfig, httpsConnConfig: HttpsConnectorConfig) = {
    new ProxyServerConfig.Builder()
      .setConnectors(new Connectors(httpConnConfig, httpsConnConfig))
  }

  def newStyxConfig(yaml: String, proxyServerConfigBuilder: ProxyServerConfig.Builder, adminServerConfigBuilder: AdminServerConfig.Builder) = {
    new StyxConfig(new MapBackedConfiguration(new YamlConfig(yaml))
      .set("proxy", proxyServerConfigBuilder.build())
      .set("admin", adminServerConfigBuilder.build()))
  }

  def newStyxServerBuilder(styxConfig: StyxConfig, backendServicesRegistry: Registry[BackendService], plugins: List[NamedPlugin] = Nil) = {
    val plugins1 = plugins.asInstanceOf[Iterable[NamedPlugin]].asJava
    val pluginSupplier = new java.util.function.Supplier[java.lang.Iterable[NamedPlugin]] {
      override def get() = plugins1
    }

    val builder = new StyxServerBuilder(styxConfig)
      .additionalServices("backendServiceRegistry", backendServicesRegistry)

    if (plugins.nonEmpty) {
      builder.pluginsSupplier(pluginSupplier)
    } else {
      builder
    }
  }

  def noOp[T] = (x: T) => x

  //  implicit class StyxServerOperations(val styxServer: StyxServer) extends StyxServerSupplements
}

trait StyxServerSupport {
  implicit class StyxServerOperations(val styxServer: StyxServer) extends StyxServerSupplements
}

trait StyxServerSupplements {
  val styxServer: StyxServer

  private def toHostAndPort(address: InetSocketAddress) = address.getHostName + ":" + address.getPort

  def httpsProxyHost: String = toHostAndPort(styxServer.proxyHttpsAddress())

  def proxyHost: String = toHostAndPort(styxServer.proxyHttpAddress())

  def adminHost: String = toHostAndPort(styxServer.adminHttpAddress())

  def secureRouterURL(path: String) = s"https://$httpsProxyHost$path"

  def routerURL(path: String) = s"http://$proxyHost$path"

  def adminURL(path: String) = s"http://$adminHost$path"

  def secureHttpPort = styxServer.proxyHttpsAddress().getPort

  def httpPort = styxServer.proxyHttpAddress().getPort

  def adminPort = styxServer.adminHttpAddress().getPort

  def metricsSnapshot = {
    val adminHostName = styxServer.adminHttpAddress().getHostName
    new CodaHaleMetricsFacade(StyxMetrics.downloadFrom(adminHostName, adminPort))
  }
}

class PluginAdapter extends Plugin {
  override def adminInterfaceHandlers(): util.Map[String, HttpHandler] = emptyMap()

  override def intercept(request: HttpRequest, chain: Chain): Observable[HttpResponse] = chain.proceed(request)

  override def styxStarting(): Unit = Unit

  override def styxStopping(): Unit = Unit
}