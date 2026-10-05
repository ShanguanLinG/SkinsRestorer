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

import com.google.gson.JsonParseException;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.property.MojangSkinDataResult;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.shared.connections.http.HttpClient;
import net.skinsrestorer.shared.connections.http.HttpResponse;
import net.skinsrestorer.shared.connections.responses.profile.MojangProfileResponse;
import net.skinsrestorer.shared.connections.responses.profile.PropertyResponse;
import net.skinsrestorer.shared.connections.responses.uuid.MojangUUIDResponse;
import net.skinsrestorer.shared.exception.DataRequestExceptionShared;
import net.skinsrestorer.shared.plugin.SRPlugin;
import net.skinsrestorer.shared.storage.adapter.AdapterReference;
import net.skinsrestorer.shared.storage.adapter.StorageAdapter;
import net.skinsrestorer.shared.storage.model.cache.MojangCacheData;
import net.skinsrestorer.shared.storage.model.skin.PlayerSkinData;
import net.skinsrestorer.shared.utils.UUIDUtils;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class PlayerSkinService {
    private static final long TTL_MILLIS = 10 * 60 * 1000;
    private final HttpClient httpClient;
    private final String userAgent;
    private final AdapterReference storage;
    private final Clock clock;
    private final String mojangRoot;
    private final String sessionRoot;
    private final String mineToolsRoot;
    private final Map<UUID, CachedSkin> skins = new ConcurrentHashMap<>();
    private final Map<String, CachedUUID> names = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<SkinProperty>>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<UUID>>> nameRequests = new ConcurrentHashMap<>();

    @Inject
    public PlayerSkinService(HttpClient httpClient, SRPlugin plugin, AdapterReference storage) {
        this(httpClient, plugin.getUserAgent(), storage, Clock.systemUTC(), "https://api.mojang.com",
                "https://sessionserver.mojang.com", "https://api.minetools.eu");
    }

    PlayerSkinService(HttpClient httpClient, String userAgent, AdapterReference storage, Clock clock,
                      String mojangRoot, String sessionRoot, String mineToolsRoot) {
        this.httpClient = httpClient;
        this.userAgent = userAgent;
        this.storage = storage;
        this.clock = clock;
        this.mojangRoot = mojangRoot;
        this.sessionRoot = sessionRoot;
        this.mineToolsRoot = mineToolsRoot;
    }

    public Optional<MojangSkinDataResult> getSkin(String input) throws DataRequestException {
        Optional<UUID> uuid = UUIDUtils.tryParseUniqueId(input);
        if (uuid.isEmpty()) {
            uuid = getUUID(input);
        }
        if (uuid.isEmpty()) {
            return Optional.empty();
        }
        UUID id = uuid.get();
        return getProfile(id, false).map(property -> MojangSkinDataResult.of(id, property));
    }

    public Optional<UUID> getUUID(String name) throws DataRequestException {
        String key = name.toLowerCase(Locale.ROOT);
        CachedUUID cached = names.get(key);
        if (cached != null && cached.expiresAt() > clock.millis()) {
            return Optional.of(cached.uuid());
        }
        CompletableFuture<Optional<UUID>> request = new CompletableFuture<>();
        CompletableFuture<Optional<UUID>> existing = nameRequests.putIfAbsent(key, request);
        if (existing != null) {
            return await(existing);
        }
        try {
            List<Throwable> errors = new ArrayList<>();
            Optional<UUID> result = attempt(() -> lookup(mojangRoot + "/users/profiles/minecraft/" + name), errors)
                    .or(() -> attempt(() -> lookup(mineToolsRoot + "/uuid/" + name), errors));
            if (result.isPresent()) {
                UUID id = result.get();
                names.put(key, new CachedUUID(id, clock.millis() + TTL_MILLIS));
                storage.getOrDefault(adapter -> {
                    adapter.setCachedUUID(key, MojangCacheData.of(id, clock.instant().getEpochSecond()));
                    return null;
                }, null);
            } else {
                result = storedUUID(key);
                if (result.isEmpty() && cached != null) {
                    result = Optional.of(cached.uuid());
                }
                failIfUnavailable(result, errors);
            }
            request.complete(result);
            return result;
        } catch (DataRequestException | RuntimeException e) {
            request.completeExceptionally(e);
            throw e;
        } finally {
            nameRequests.remove(key, request);
        }
    }

    public Optional<SkinProperty> getProfile(UUID uuid, boolean fresh) throws DataRequestException {
        CachedSkin cached = skins.get(uuid);
        if (!fresh && cached != null && cached.expiresAt() > clock.millis()) {
            return Optional.of(cached.property());
        }
        CompletableFuture<Optional<SkinProperty>> request = new CompletableFuture<>();
        CompletableFuture<Optional<SkinProperty>> existing = inFlight.putIfAbsent(uuid, request);
        if (existing != null) {
            return await(existing);
        }
        try {
            List<Throwable> errors = new ArrayList<>();
            String id = UUIDUtils.convertToNoDashes(uuid);
            Optional<SkinProperty> result = attempt(() -> profile(sessionRoot
                    + "/session/minecraft/profile/" + id + "?unsigned=false", uuid, false), errors)
                    .or(() -> attempt(() -> profile(mineToolsRoot + "/profile/" + id, uuid, true), errors));
            if (result.isPresent()) {
                SkinProperty property = result.get();
                net.skinsrestorer.api.model.MojangProfileResponse data = PropertyUtils.getSkinProfileData(property);
                Optional<PlayerSkinData> stored = storedSkin(uuid);
                if (stored.isPresent() && PropertyUtils.getSkinProfileData(stored.get().getProperty())
                        .getTimestamp() >= data.getTimestamp()) {
                    property = stored.get().getProperty();
                } else {
                    SkinProperty resolved = property;
                    storage.getOrDefault(adapter -> {
                        adapter.setPlayerSkinData(uuid, PlayerSkinData.of(uuid, data.getProfileName(), resolved,
                                clock.instant().getEpochSecond()));
                        return null;
                    }, null);
                }
                skins.put(uuid, new CachedSkin(property, clock.millis() + TTL_MILLIS));
                String name = data.getProfileName().toLowerCase(Locale.ROOT);
                names.put(name, new CachedUUID(uuid, clock.millis() + TTL_MILLIS));
                storage.getOrDefault(adapter -> {
                    adapter.setCachedUUID(name, MojangCacheData.of(uuid, clock.instant().getEpochSecond()));
                    return null;
                }, null);
                result = Optional.of(property);
            } else {
                result = storedSkin(uuid).map(PlayerSkinData::getProperty);
                if (result.isEmpty() && cached != null) {
                    result = Optional.of(cached.property());
                }
                failIfUnavailable(result, errors);
            }
            request.complete(result);
            return result;
        } catch (DataRequestException | RuntimeException e) {
            request.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(uuid, request);
        }
    }

    private Optional<UUID> storedUUID(String name) throws DataRequestException {
        try {
            return storage.getOrDefaultThrowing(adapter -> adapter.getCachedUUID(name)
                    .flatMap(MojangCacheData::getUniqueId), Optional.empty());
        } catch (StorageAdapter.StorageException e) {
            throw new DataRequestExceptionShared(e);
        }
    }

    private Optional<PlayerSkinData> storedSkin(UUID uuid) throws DataRequestException {
        try {
            return storage.getOrDefaultThrowing(adapter -> adapter.getPlayerSkinData(uuid), Optional.empty());
        } catch (StorageAdapter.StorageException e) {
            throw new DataRequestExceptionShared(e);
        }
    }

    private Optional<UUID> lookup(String address) throws DataRequestException {
        HttpResponse response = read(address);
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return Optional.empty();
        }
        MojangUUIDResponse data = response.getBodyAs(MojangUUIDResponse.class);
        return data == null || data.getId() == null ? Optional.empty() : UUIDUtils.tryParseUniqueId(data.getId());
    }

    private Optional<SkinProperty> profile(String address, UUID uuid, boolean mineTools) throws DataRequestException {
        HttpResponse response = read(address);
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return Optional.empty();
        }
        MojangProfileResponse data = mineTools ? Optional.ofNullable(response.getBodyAs(MineToolsProfile.class))
                .map(MineToolsProfile::raw).orElse(null) : response.getBodyAs(MojangProfileResponse.class);
        if (data == null || data.getId() == null || !UUIDUtils.tryParseUniqueId(data.getId()).equals(Optional.of(uuid))
                || data.getProperties() == null) {
            return Optional.empty();
        }
        for (PropertyResponse property : data.getProperties()) {
            if (property == null || !"textures".equals(property.getName()) || property.getValue() == null
                    || property.getValue().isBlank() || property.getSignature() == null
                    || property.getSignature().isBlank()) {
                continue;
            }
            SkinProperty skin = SkinProperty.of(property.getValue(), property.getSignature());
            try {
                net.skinsrestorer.api.model.MojangProfileResponse decoded = PropertyUtils.getSkinProfileData(skin);
                if (decoded != null && decoded.getProfileId() != null && UUIDUtils.tryParseUniqueId(decoded.getProfileId()).equals(Optional.of(uuid))
                        && decoded.getProfileName() != null && !decoded.getProfileName().isBlank()
                        && decoded.getTextures() != null && decoded.getTextures().getSKIN() != null) {
                    return Optional.of(skin);
                }
            } catch (IllegalArgumentException | JsonParseException e) {
                throw new DataRequestExceptionShared(e);
            }
        }
        return Optional.empty();
    }

    private HttpResponse read(String address) throws DataRequestException {
        try {
            HttpResponse response = httpClient.execute(URI.create(address), null, HttpClient.HttpType.JSON,
                    userAgent, HttpClient.HttpMethod.GET, Collections.emptyMap(), 5000);
            if (response.statusCode() != 200 && response.statusCode() != 204 && response.statusCode() != 404) {
                throw new DataRequestExceptionShared("HTTP " + response.statusCode());
            }
            return response;
        } catch (IOException e) {
            throw new DataRequestExceptionShared(e);
        }
    }

    private static <T> Optional<T> attempt(Request<T> request, List<Throwable> errors) {
        try {
            return request.get();
        } catch (DataRequestException e) {
            errors.add(e);
            return Optional.empty();
        }
    }

    private static void failIfUnavailable(Optional<?> result, List<Throwable> errors) throws DataRequestException {
        if (result.isEmpty() && !errors.isEmpty()) {
            DataRequestExceptionShared exception = new DataRequestExceptionShared(errors.getFirst());
            errors.stream().skip(1).forEach(exception::addSuppressed);
            throw exception;
        }
    }

    private static <T> T await(CompletableFuture<T> request) throws DataRequestException {
        try {
            return request.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataRequestExceptionShared(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DataRequestException exception) {
                throw exception;
            }
            throw new DataRequestExceptionShared(e.getCause());
        }
    }

    private interface Request<T> {
        Optional<T> get() throws DataRequestException;
    }

    private record CachedSkin(SkinProperty property, long expiresAt) {
    }

    private record CachedUUID(UUID uuid, long expiresAt) {
    }

    private record MineToolsProfile(MojangProfileResponse raw) {
    }
}
