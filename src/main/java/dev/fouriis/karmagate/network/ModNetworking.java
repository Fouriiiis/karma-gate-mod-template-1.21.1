package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.gridproject.ProjectionZoneManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Handles server-side networking for projection zones.
 */
public class ModNetworking {
    
    /**
     * Registers all network payloads and handlers.
     * Call this during mod initialization.
     */
    public static void register() {
        // Register the sync payload type (server -> client)
        PayloadTypeRegistry.playS2C().register(
            ProjectionZoneSyncPayload.ID, 
            ProjectionZoneSyncPayload.CODEC
        );
        
        // Sync zones to players when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncToPlayer(handler.getPlayer());
        });
    }
    
    /**
     * Syncs all projection zones to a specific player.
     */
    public static void syncToPlayer(ServerPlayerEntity player) {
        ProjectionZoneManager manager = ProjectionZoneManager.get(player.getServer());
        ProjectionZoneSyncPayload payload = ProjectionZoneSyncPayload.fromZones(manager.getAllZones());
        ServerPlayNetworking.send(player, payload);
    }
    
    /**
     * Syncs all projection zones to all players on the server.
     */
    public static void syncToAll(net.minecraft.server.MinecraftServer server) {
        ProjectionZoneManager manager = ProjectionZoneManager.get(server);
        ProjectionZoneSyncPayload payload = ProjectionZoneSyncPayload.fromZones(manager.getAllZones());
        
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
