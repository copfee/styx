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
package com.hotels.styx.proxy;

import com.hotels.styx.Environment;
import com.hotels.styx.api.HttpClient;
import com.hotels.styx.api.HttpHandler2;
import com.hotels.styx.api.HttpInterceptor;
import com.hotels.styx.api.HttpRequest;
import com.hotels.styx.api.HttpResponse;
import com.hotels.styx.api.metrics.codahale.CodaHaleMetricRegistry;
import com.hotels.styx.client.applications.BackendService;
import com.hotels.styx.infrastructure.Registry;
import org.testng.annotations.Test;
import rx.Observable;

import java.util.Optional;

import static com.hotels.styx.api.HttpRequest.Builder.get;
import static com.hotels.styx.api.HttpResponse.Builder.response;
import static com.hotels.styx.client.StyxHeaderConfig.ORIGIN_ID_DEFAULT;
import static com.hotels.styx.api.client.Origin.newOriginBuilder;
import static com.hotels.styx.client.applications.BackendService.newBackendServiceBuilder;
import static com.hotels.styx.support.matchers.IsOptional.isValue;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rx.Observable.just;

public class BackendServicesRouterTest {
    private static final String APP_A = "appA";
    private static final String APP_B = "appB";

    private final BackendServiceClientFactory serviceClientFactory = backendService -> request -> responseWithOriginIdHeader(backendService);
    private HttpInterceptor.Context context;

    @Test
    public void registersAllRoutes() {
        Registry.Changes<BackendService> changes = added(
                appA().newCopy().path("/headers").build(),
                appB().newCopy().path("/badheaders").build(),
                appB().newCopy().path("/cookies").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        assertThat(router.routes().keySet(), contains("/badheaders", "/cookies", "/headers"));
    }

    @Test
    public void selectsServiceBasedOnPath() {
        Registry.Changes<BackendService> changes = added(
                appA().newCopy().path("/").build(),
                appB().newCopy().path("/appB/hotel/details.html").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        HttpRequest request = get("/appB/hotel/details.html").build();
        Optional<HttpHandler2> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    @Test
    public void selectsApplicationBasedOnPathIfAppsAreProvidedInOppositeOrder() {
        Registry.Changes<BackendService> changes = added(
                appB().newCopy().path("/appB/hotel/details.html").build(),
                appA().newCopy().path("/").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        HttpRequest request = get("/appB/hotel/details.html").build();
        Optional<HttpHandler2> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }


    @Test
    public void selectsUsingSingleSlashPath() {
        Registry.Changes<BackendService> changes = added(
                appA().newCopy().path("/").build(),
                appB().newCopy().path("/appB/hotel/details.html").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        HttpRequest request = get("/").build();
        Optional<HttpHandler2> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
    }

    @Test
    public void selectsUsingSingleSlashPathIfAppsAreProvidedInOppositeOrder() {
        Registry.Changes<BackendService> changes = added(
                appB().newCopy().path("/appB/hotel/details.html").build(),
                appA().newCopy().path("/").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        HttpRequest request = get("/").build();
        Optional<HttpHandler2> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_A));
    }

    @Test
    public void selectsUsingPathWithNoSubsequentCharacters() {
        Registry.Changes<BackendService> changes = added(
                appA().newCopy().path("/").build(),
                appB().newCopy().path("/appB/").build());

        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(changes);

        HttpRequest request = get("/appB/").build();
        Optional<HttpHandler2> route = router.route(request);

        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    @Test
    public void doesNotMatchRequestIfFinalSlashIsMissing() {
        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(added(appB().newCopy().path("/appB/hotel/details.html").build()));

        HttpRequest request = get("/ba/").build();
        Optional<HttpHandler2> route = router.route(request);
        System.out.println("route: " + route);

        assertThat(route, is(Optional.empty()));
    }

    @Test
    public void throwsExceptionWhenNoApplicationMatches() {
        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);
        router.onChange(added(appB().newCopy().path("/appB/hotel/details.html").build()));

        HttpRequest request = get("/qwertyuiop").build();
        assertThat(router.route(request), is(Optional.empty()));
    }

    @Test
    public void updatesRoutesOnBackendServicesChange() {
        BackendServicesRouter router = new BackendServicesRouter(serviceClientFactory);

        HttpRequest request = get("/appB/").build();


        router.onChange(added(appB()));

        Optional<HttpHandler2> route = router.route(request);
        assertThat(proxyTo(route, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));

        router.onChange(new Registry.Changes.Builder<BackendService>().build());

        Optional<HttpHandler2> route2 = router.route(request);
        assertThat(proxyTo(route2, request).header(ORIGIN_ID_DEFAULT), isValue(APP_B));
    }

    private HttpResponse proxyTo(Optional<HttpHandler2> pipeline, HttpRequest request) {
        return pipeline.get().handle(request, context).toBlocking().first();
    }

    @Test
    public void closesClientWhenBackendServicesAreUpdated() {
        HttpClient firstClient = mock(HttpClient.class);
        HttpClient secondClient = mock(HttpClient.class);

        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
        when(clientFactory.createClient(any(BackendService.class)))
                .thenReturn(firstClient)
                .thenReturn(secondClient);

        BackendServicesRouter router = new BackendServicesRouter(clientFactory);

        BackendService bookingApp = appB();
        router.onChange(added(bookingApp));

        verify(clientFactory).createClient(bookingApp);

        BackendService bookingAppMinusOneOrigin = bookingAppMinusOneOrigin();

        router.onChange(updated(bookingAppMinusOneOrigin));

        verify(firstClient).close();
        verify(clientFactory).createClient(bookingAppMinusOneOrigin);
    }

    @Test
    public void closesClientWhenBackendServicesAreRemoved() {
        HttpClient firstClient = mock(HttpClient.class);
        HttpClient secondClient = mock(HttpClient.class);

        BackendServiceClientFactory clientFactory = mock(BackendServiceClientFactory.class);
        when(clientFactory.createClient(any(BackendService.class)))
                .thenReturn(firstClient)
                .thenReturn(secondClient);

        BackendServicesRouter router = new BackendServicesRouter(clientFactory);

        BackendService bookingApp = appB();
        router.onChange(added(bookingApp));

        verify(clientFactory).createClient(bookingApp);

        router.onChange(removed(bookingApp));

        verify(firstClient).close();
    }

    // This test exists due to a real bug we had when reloading in prod
    @Test
    public void deregistersAndReregistersMetricsAppropriately() {
        CodaHaleMetricRegistry metrics = new CodaHaleMetricRegistry();

        BackendServicesRouter router = new BackendServicesRouter(
                new StyxBackendServiceClientFactory(
                        new Environment.Builder()
                                .metricsRegistry(metrics)
                                .build(), 1));

        router.onChange(added(backendService(APP_B, "/appB/", 9094, "appB-01", 9095, "appB-02")));

        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
        assertThat(metrics.getGauges().get("origins.appB.appB-02.status").getValue(), is(1));

        BackendService appMinusOneOrigin = backendService(APP_B, "/appB/", 9094, "appB-01");

        router.onChange(updated(appMinusOneOrigin));

        assertThat(metrics.getGauges().get("origins.appB.appB-01.status").getValue(), is(1));
        assertThat(metrics.getGauges().get("origins.appB.appB-02.status"), is(nullValue()));
    }

    private static Registry.Changes<BackendService> added(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().added(backendServices).build();
    }

    private static Registry.Changes<BackendService> updated(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().updated(backendServices).build();
    }

    private static Registry.Changes<BackendService> removed(BackendService... backendServices) {
        return new Registry.Changes.Builder<BackendService>().removed(backendServices).build();
    }

    private static BackendService appA() {
        return newBackendServiceBuilder()
                .id(APP_A)
                .path("/")
                .origins(newOriginBuilder("localhost", 9090).applicationId(APP_A).id("appA-01").build())
                .build();
    }

    private static BackendService appB() {
        return newBackendServiceBuilder()
                .id(APP_B)
                .path("/appB/")
                .origins(
                        newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build(),
                        newOriginBuilder("localhost", 9095).applicationId(APP_B).id("appB-02").build())
                .build();
    }

    private static BackendService backendService(String id, String path, int originPort1, String originId1, int originPort2, String originId2) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(
                        newOriginBuilder("localhost", originPort1).applicationId(id).id(originId1).build(),
                        newOriginBuilder("localhost", originPort2).applicationId(id).id(originId2).build())
                .build();
    }

    private static BackendService bookingAppMinusOneOrigin() {
        return newBackendServiceBuilder()
                .id(APP_B)
                .path("/appB/")
                .origins(newOriginBuilder("localhost", 9094).applicationId(APP_B).id("appB-01").build())
                .build();
    }

    private static BackendService backendService(String id, String path, int originPort, String originId) {
        return newBackendServiceBuilder()
                .id(id)
                .path(path)
                .origins(newOriginBuilder("localhost", originPort).applicationId(id).id(originId).build())
                .build();
    }

    private static Observable<HttpResponse> responseWithOriginIdHeader(BackendService backendService) {
        return just(response(OK)
                .header(ORIGIN_ID_DEFAULT, backendService.id())
                .build());
    }
}