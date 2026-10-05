/*
 * SkinsRestorer
 * Copyright (C) 2026  SkinsRestorer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.skinsrestorer.shared.connections;

import com.sun.net.httpserver.HttpServer;
import net.skinsrestorer.SRExtension;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.shared.connections.http.HttpClient;
import net.skinsrestorer.shared.connections.http.HttpResponse;
import net.skinsrestorer.shared.storage.adapter.AdapterReference;
import net.skinsrestorer.shared.storage.adapter.StorageAdapter;
import net.skinsrestorer.shared.storage.model.cache.MojangCacheData;
import net.skinsrestorer.shared.storage.model.skin.PlayerSkinData;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(SRExtension.class)
class PlayerSkinServiceTest {
    private static final UUID UUID_VALUE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private final Map<UUID, PlayerSkinData> savedSkins = new ConcurrentHashMap<>();
    private final Map<String, MojangCacheData> savedNames = new ConcurrentHashMap<>();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger requests = new AtomicInteger();
    private final AdapterReference storage = new AdapterReference();
    private HttpServer server;
    private HttpClient client;
    private volatile boolean mojangFails;
    private volatile boolean mineToolsFails;
    private volatile boolean invalidProfile;
    private volatile String invalidBody;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;
    private PlayerSkinService service;

    @BeforeEach
    void setup() throws Exception {
        StorageAdapter adapter = mock(StorageAdapter.class);
        storage.setAdapter(adapter);
        when(adapter.getPlayerSkinData(any())).thenAnswer(call -> Optional.ofNullable(savedSkins.get(call.getArgument(0))));
        when(adapter.getCachedUUID(any())).thenAnswer(call -> Optional.ofNullable(savedNames.get(call.getArgument(0))));
        doAnswer(call -> {
            savedSkins.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(adapter).setPlayerSkinData(any(), any());
        doAnswer(call -> {
            savedNames.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(adapter).setCachedUUID(any(), any());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            boolean mineTools = path.startsWith("/mine");
            boolean fails = mineTools ? mineToolsFails : mojangFails;
            if (path.contains("/profile/") && entered != null) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = path.contains("/uuid/") || path.contains("/users/")
                    ? "{\"id\":\"" + UUID_VALUE + "\",\"name\":\"Notch\"}"
                    : profile(mineTools);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fails ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = mock(HttpClient.class);
        java.net.http.HttpClient localClient = java.net.http.HttpClient.newHttpClient();
        when(client.execute(any(), any(), any(), any(), any(), any(), anyInt())).thenAnswer(call -> {
            assertEquals(5000, call.getArgument(6, Integer.class));
            URI uri = call.getArgument(0);
            var response = localClient.send(HttpRequest.newBuilder(uri).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return new HttpResponse(response.statusCode(), response.body(), response.headers().map());
        });
        service = newService();
    }

    @AfterEach
    void teardown() {
        server.stop(0);
    }

    private PlayerSkinService newService() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        };
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        return new PlayerSkinService(client, "test", storage, clock, root + "/mojang", root + "/session", root + "/mine");
    }

    private SkinProperty property() {
        String json = "{\"timestamp\":1000,\"profileId\":\"" + UUID_VALUE
                + "\",\"profileName\":\"Notch\",\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/test\"}}}";
        return SkinProperty.of(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), "signed");
    }

    private String profile(boolean mineTools) {
        if (invalidBody != null) {
            return invalidBody;
        }
        String json = "{\"id\":\"" + (invalidProfile ? UUID.randomUUID() : UUID_VALUE)
                + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + property().getValue()
                + "\",\"signature\":\"signed\"}]}";
        return mineTools ? "{\"raw\":" + json + "}" : json;
    }

    @Test
    void officialSkinIsSharedByNameAndUUID() throws Exception {
        assertEquals(property(), service.getSkin("Notch").orElseThrow().getSkinProperty());
        assertEquals(property(), service.getSkin(UUID_VALUE.toString()).orElseThrow().getSkinProperty());
        assertEquals(property(), service.getSkin("nOtCh").orElseThrow().getSkinProperty());
        assertEquals(2, requests.get());
        assertTrue(savedSkins.containsKey(UUID_VALUE));
    }

    @Test
    void mineToolsWorksWhenMojangFails() throws Exception {
        mojangFails = true;
        assertEquals(property(), service.getSkin("Notch").orElseThrow().getSkinProperty());
        assertEquals(4, requests.get());
    }

    @Test
    void cacheExpiresAtTenMinutesAndFreshBypassesIt() throws Exception {
        service.getProfile(UUID_VALUE, false);
        now.addAndGet(599_999);
        service.getProfile(UUID_VALUE, false);
        assertEquals(1, requests.get());
        now.incrementAndGet();
        service.getProfile(UUID_VALUE, false);
        assertEquals(2, requests.get());
        service.getProfile(UUID_VALUE, true);
        assertEquals(3, requests.get());
    }

    @Test
    void restartAndExpiredMappingKeepOldSkinWithoutChangingTimestamp() throws Exception {
        service.getSkin("Notch");
        PlayerSkinData skin = savedSkins.get(UUID_VALUE);
        MojangCacheData name = savedNames.get("notch");
        now.addAndGet(3_600_000);
        mojangFails = true;
        mineToolsFails = true;
        service = newService();
        assertEquals(property(), service.getSkin("NOTCH").orElseThrow().getSkinProperty());
        assertSame(skin, savedSkins.get(UUID_VALUE));
        assertSame(name, savedNames.get("notch"));
        assertEquals(property(), service.getProfile(UUID_VALUE, true).orElseThrow());
    }

    @Test
    void noSavedSkinPropagatesNetworkFailure() {
        mojangFails = true;
        mineToolsFails = true;
        assertThrows(DataRequestException.class, () -> service.getSkin("Notch"));
        assertTrue(savedNames.isEmpty());
        assertTrue(savedSkins.isEmpty());
    }

    @Test
    void mismatchedProfilesCannotReplaceSavedSkin() throws Exception {
        service.getSkin("Notch");
        PlayerSkinData previous = savedSkins.get(UUID_VALUE);
        invalidProfile = true;
        assertEquals(property(), service.getProfile(UUID_VALUE, true).orElseThrow());
        assertSame(previous, savedSkins.get(UUID_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{", "{\"raw\":{\"properties\":[]}}"})
    void invalidResponsesDoNotOverwriteSavedSkin(String body) throws Exception {
        service.getSkin("Notch");
        PlayerSkinData previous = savedSkins.get(UUID_VALUE);
        invalidBody = body;
        assertEquals(property(), service.getProfile(UUID_VALUE, true).orElseThrow());
        assertSame(previous, savedSkins.get(UUID_VALUE));
    }

    @Test
    void concurrentProfilesShareOneRequest() throws Exception {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<SkinProperty>> first = executor.submit(() -> service.getProfile(UUID_VALUE, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<Optional<SkinProperty>> second = executor.submit(() -> {
                secondStarted.countDown();
                return service.getProfile(UUID_VALUE, false);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
        }
    }
}
