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

import ch.jalu.configme.SettingsManager;
import ch.jalu.injector.Injector;
import net.skinsrestorer.SRExtension;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.shared.config.StorageConfig;
import net.skinsrestorer.shared.exception.DataRequestExceptionShared;
import net.skinsrestorer.shared.log.SRLogger;
import net.skinsrestorer.shared.storage.CacheStorageImpl;
import net.skinsrestorer.shared.storage.SkinStorageImpl;
import net.skinsrestorer.shared.storage.adapter.AdapterReference;
import net.skinsrestorer.shared.storage.adapter.StorageAdapter;
import net.skinsrestorer.shared.storage.model.cache.MojangCacheData;
import net.skinsrestorer.shared.storage.model.skin.PlayerSkinData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(SRExtension.class)
class SkinStorageFallbackTest {
    private final UUID uuid = UUID.randomUUID();
    private final StorageAdapter adapter = mock(StorageAdapter.class);
    private final MojangAPIImpl api = mock(MojangAPIImpl.class);
    private final SettingsManager settings = mock(SettingsManager.class);
    private final AdapterReference reference = new AdapterReference();
    private CacheStorageImpl cache;
    private SkinStorageImpl storage;
    private SkinProperty property;

    @BeforeEach
    void setup() throws Exception {
        reference.setAdapter(adapter);
        SRLogger logger = mock(SRLogger.class);
        cache = new CacheStorageImpl(logger, api, settings, reference, mock(Injector.class));
        storage = new SkinStorageImpl(logger, cache, api, mock(MineSkinAPIImpl.class), settings, reference,
                mock(RecommendationsState.class));
        String data = "{\"timestamp\":1,\"profileId\":\"" + uuid + "\",\"profileName\":\"Notch\"}";
        property = SkinProperty.of(Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)), "signed");
        when(adapter.getCachedUUID("Notch")).thenReturn(Optional.of(MojangCacheData.of(uuid, 1)));
        when(adapter.getPlayerSkinData(uuid)).thenReturn(Optional.of(PlayerSkinData.of(uuid, "Notch", property, 1)));
        when(settings.getProperty(StorageConfig.UUID_EXPIRES_AFTER)).thenReturn(60);
        when(settings.getProperty(StorageConfig.SKIN_EXPIRES_AFTER)).thenReturn(15);
        when(settings.getProperty(StorageConfig.DISALLOW_AUTO_UPDATE_SKIN)).thenReturn(false);
    }

    @Test
    void expiredUUIDSurvivesLookupException() throws Exception {
        when(api.getUUID("Notch")).thenThrow(new DataRequestExceptionShared("offline"));
        assertEquals(Optional.of(uuid), cache.getUUID("Notch", false));
        verify(adapter, never()).setCachedUUID(any(), any());
    }

    @Test
    void emptyUUIDLookupDoesNotEraseExistingMapping() throws Exception {
        when(api.getUUID("Notch")).thenReturn(Optional.empty());
        assertEquals(Optional.of(uuid), cache.getUUID("Notch", false));
        verify(adapter, never()).setCachedUUID(any(), any());
    }

    @Test
    void refreshExceptionReturnsOldSkin() throws Exception {
        when(api.getProfileFresh(uuid)).thenThrow(new DataRequestExceptionShared("offline"));
        assertEquals(Optional.of(property), storage.updatePlayerSkinData(uuid));
        verify(adapter, never()).setPlayerSkinData(any(), any());
    }

    @Test
    void allowExpiredNameStillFallsBackWhenProfileFails() throws Exception {
        when(api.getProfile(uuid)).thenThrow(new DataRequestExceptionShared("offline"));
        assertEquals(property, storage.getPlayerSkin("Notch", true).orElseThrow().getSkinProperty());
        verify(adapter, never()).setPlayerSkinData(any(), any());
    }
}
