package net.domracz.translationDetector;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SignDoneListener implements PacketListener {
    private static HashMap<UUID, CompletableFuture<List<Integer>>> currentFutures = new HashMap<>();

    public static HashMap<UUID, CompletableFuture<List<Integer>>> getFutures() {
        return currentFutures;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.UPDATE_SIGN) {
            WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
            Player p = event.getPlayer();
            CompletableFuture<List<Integer>> state = currentFutures.get(p.getUniqueId());
            if (state != null) {
                List<Integer> nonFallbacks = new ArrayList<>();
                for (int i = 0; i < packet.getTextLines().length; i++) {
                    if (!packet.getTextLines()[i].equals("notfound")) {
                        nonFallbacks.add(i);
                    }
                }
                if (!state.isDone()) {
                    state.complete(nonFallbacks);
                }
            }
        }
    }
}
