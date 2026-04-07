package net.domracz.translationDetector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.units.qual.N;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TranslationDetector extends JavaPlugin implements Listener {
    private static HashMap<String, HashMap<String, String>> aliasToReal = new HashMap<>();
    private static List<String> cheatAliases;
    private static List<UUID> enabledLogs = new ArrayList<>();
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
        ConfigurationSection aliases = getConfig().getConfigurationSection("aliases");
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String lang : aliases.getKeys(false)) {
            ConfigurationSection internalAlias = aliases.getConfigurationSection(lang);

            HashMap<String, String> innerMap = new HashMap<>();

            for (String key : internalAlias.getKeys(false)) {
                innerMap.put(key, internalAlias.getString(key));
            }

            aliasToReal.put(lang, innerMap);
        }
        cheatAliases = getConfig().getStringList("cheataliases");
        PacketEvents.getAPI().getEventManager().registerListener(new SignDoneListener(), PacketListenerPriority.MONITOR);
        // Plugin startup logic
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, (cont) -> {
            Commands commands = cont.registrar();
            LiteralCommandNode<CommandSourceStack> direct = Commands.literal("direct").then(Commands.argument("player", ArgumentTypes.players())
                    .then(Commands.argument("translation", StringArgumentType.greedyString())
                            .executes((cmd) -> {
                                List<Player> players = cmd.getArgument("player", PlayerSelectorArgumentResolver.class)
                                        .resolve(cmd.getSource());
                                String key = StringArgumentType.getString(cmd, "translation");
                                List<String> keys = List.of(key.split(" "));
                                HashMap<Integer, RealAndAlias> map = new HashMap<>();
                                for (int i = 0; i < keys.size(); i++) {
                                    map.put(i, new RealAndAlias(keys.get(i), keys.get(i)));
                                }
                                runCheck(cmd.getSource().getSender(), players, map);
                                return 1;
                            })
                    ).executes((cmd) -> {
                        cmd.getSource().getSender().sendMessage(Component.text("Please enter a translation key to send to the player."));
                        return 0;
                    }).build()
            ).executes((cmd) -> {
                cmd.getSource().getSender().sendMessage(Component.text("Please enter a player to test", NamedTextColor.RED));
                return 0;
            }).build();

            RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> aliasbuilder = Commands.argument("player", ArgumentTypes.players());
            aliasToReal.forEach((k, v) -> {
                aliasbuilder.then(Commands.literal(k)
                        .executes((cmd) -> {
                            List<Player> players = cmd.getArgument("player", PlayerSelectorArgumentResolver.class)
                                    .resolve(cmd.getSource());
                            Map<String, String> real = aliasToReal.get(k);
                            HashMap<Integer, RealAndAlias> map = getIntegerRealAndAliasHashMap(k, real);
                            runCheck(cmd.getSource().getSender(), players, map);
                            return 1;
                        })
                );
            });
            aliasbuilder.executes((cmd) -> {
                cmd.getSource().getSender().sendMessage(Component.text("Please enter an alias to check against"));
                return 0;
            });

            commands.register(Commands.literal("translatedetector")
                    .requires((sourceStack) -> {
                        if (!(sourceStack.getSender() instanceof Player p)) {
                            return true;
                        }
                        return p.hasPermission("translationdetector");
                    }).then(Commands.literal("alias")
                            .then(aliasbuilder.build())
                            .executes((cmd) -> {
                                cmd.getSource().getSender().sendMessage(Component.text("Please enter a player to check"));
                                return 0;
                            })
                    ).then(direct).then(Commands.literal("log")
                            .executes((cmd) -> {
                                if (!(cmd.getSource().getSender() instanceof Player p)) {
                                    cmd.getSource().getSender().sendMessage(Component.text("Only players can use this!", NamedTextColor.RED));
                                    return 0;
                                }
                                if (enabledLogs.contains(p.getUniqueId())) {
                                    enabledLogs.remove(p.getUniqueId());
                                    cmd.getSource().getSender().sendMessage(Component.text("You will no longer receive logs when players join about possible cheats.", NamedTextColor.RED));
                                }else{
                                    enabledLogs.add(p.getUniqueId());
                                    cmd.getSource().getSender().sendMessage(Component.text("You will now receive logs when players join about possible cheats.", NamedTextColor.GREEN));
                                }
                                return 1;
                            })
                    ).build()
            );
        });
    }
    @EventHandler
    public void handleJoin(PlayerJoinEvent e) {
        List<Player> players = List.of(e.getPlayer());
        HashMap<Integer, RealAndAlias> map = new HashMap<>();
        List<RealAndAlias> realAndAliases = new ArrayList<>();
        for (String calias : cheatAliases) {
            Map<String, String> real = aliasToReal.get(calias);
            for (Map.Entry<String, String> entry : real.entrySet()) {
                realAndAliases.add(new RealAndAlias(entry.getValue(), calias + "-" + entry.getKey()));
            }
        }
        for (int i = 0; i < realAndAliases.size(); i++) {
            map.put(i, realAndAliases.get(i));
        }
        List<CommandSender> cmds = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach((p) -> {
            if (enabledLogs.contains(p.getUniqueId())) cmds.add(p);
        });
        cmds.add(Bukkit.getConsoleSender());

        runCheck(cmds, players, map);
    }

    private static @NotNull HashMap<Integer, RealAndAlias> getIntegerRealAndAliasHashMap(String k, Map<String, String> real) {
        List<RealAndAlias> realAndAliases = new ArrayList<>();
        for (Map.Entry<String, String> entry : real.entrySet()) {
            realAndAliases.add(new RealAndAlias(entry.getValue(), k + "-" + entry.getKey()));
        }
        HashMap<Integer, RealAndAlias> map = new HashMap<>();
        for (int i = 0; i < realAndAliases.size(); i++) {
            map.put(i, realAndAliases.get(i));
        }
        return map;
    }

    public void runCheck(CommandSender cmdsender, List<Player> players, HashMap<Integer, RealAndAlias> map) {
        runCheck(List.of(cmdsender), players, map);
    }

    public void runCheck(List<CommandSender> cmdsenders, List<Player> players, HashMap<Integer, RealAndAlias> map) {
        CompletableFuture<Void> whenDone = new CompletableFuture<>();
        AtomicInteger done = new AtomicInteger();
        for (Player p : players) {
            testPlayerForTranslation(p, map).thenAccept((list) -> {
                List<TextComponent> finalElems = list.stream().filter(map::containsKey).map((e) -> Component.text(map.get(e).alias(), NamedTextColor.RED)).toList();
                List<TextComponent> finalElemsClamped = finalElems.subList(0, Math.min(finalElems.size(), 10));
                for (CommandSender cmdsender : cmdsenders) {
                    Player sender = (cmdsender instanceof Player) ? (Player) cmdsender : null;
                    cmdsender.sendMessage(Component.text("                                                                        ").style(Style.style(NamedTextColor.DARK_AQUA, TextDecoration.STRIKETHROUGH)));
                    cmdsender.sendMessage(
                            Component.text("Player ", NamedTextColor.BLUE)
                                    .append(p.displayName())
                                    .append(Component.text(" | Found ", NamedTextColor.BLUE))
                                    .append(Component.text(list.size(), list.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED))
                                    .append(Component.text(" matches. ", NamedTextColor.BLUE))
                                    .append(Component.text("[HOVER/CLICK]").style(Style.style(NamedTextColor.BLUE, TextDecoration.BOLD))
                                            .hoverEvent(HoverEvent.showText(
                                                    componentListToNL(finalElemsClamped)
                                            )).clickEvent(ClickEvent.callback((adv) -> {
                                                assert sender != null; //Would never happen, console cannot callback
                                                showTextList(sender, finalElems, 0);
                                            })))
                    );
                    cmdsender.sendMessage(Component.text("                                                                      ").style(Style.style(NamedTextColor.DARK_AQUA, TextDecoration.STRIKETHROUGH)));
                }
            });
        }
    }

    public void showTextList(Player p, List<TextComponent> list, int page) {
        List<TextComponent> toShow = list.subList(page * 10, Math.min(list.size(), (page + 1) * 10));
        p.sendMessage(Component.text("                                        ").style(Style.style(NamedTextColor.DARK_AQUA, TextDecoration.STRIKETHROUGH)));
        p.sendMessage(Component.text("Page " + (page + 1) + "/" + (((list.size() - 1) / 10) + 1), NamedTextColor.GREEN));
        toShow.forEach(p::sendMessage);
        p.sendMessage(Component.text()
                .append(Component.text("|                 «", (page > 0) ? NamedTextColor.GREEN : NamedTextColor.GRAY).clickEvent(ClickEvent.callback((call) -> {
                    if (page > 0) {
                        showTextList(p, list, (page - 1));
                    }
                })))
                .append(Component.text("  "))
                .append(Component.text("»                 |", (page < ((list.size() - 1) / 10)) ? NamedTextColor.GREEN : NamedTextColor.GRAY).clickEvent(ClickEvent.callback((call) -> {
                    if (page < ((list.size() - 1) / 10)) {
                        showTextList(p, list, (page + 1));
                    }
                })))
                .append(Component.text(""))
        );
        p.sendMessage(Component.text("                                        ").style(Style.style(NamedTextColor.DARK_AQUA, TextDecoration.STRIKETHROUGH)));
    }

    public TextComponent componentListToNL(List<TextComponent> comps) {
        return comps.stream().reduce((c1, c2) -> {
            return c1.appendNewline().append(c2);
        }).orElse(Component.empty());
    }


    public CompletableFuture<List<Integer>> testPlayerForTranslation(Player p, HashMap<Integer, RealAndAlias> translations) {
        List<List<Map.Entry<Integer, String>>> entry = chunkList(translations.entrySet().stream().map((e) -> Map.entry(e.getKey(), e.getValue().real())).toList(), 4);
        CompletableFuture<List<Integer>> results = CompletableFuture.completedFuture(new ArrayList<>());
        Vector3i loc = new Vector3i(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        WrappedBlockState ogState = SpigotConversionUtil.fromBukkitBlockData(p.getWorld().getBlockData(loc.x, loc.y, loc.z));
        WrappedBlockState state = SpigotConversionUtil.fromBukkitBlockData(BlockType.OAK_SIGN.createBlockData());
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(loc, state);

        PacketEvents.getAPI().getPlayerManager().sendPacket(p, blockChange);
        int shift = 0;
        for (List<Map.Entry<Integer, String>> line : entry) {
            int finalShift = shift;
            results = results.thenCompose((func) -> {
                CompletableFuture<List<Integer>> future = new CompletableFuture<>();
                SignDoneListener.getFutures().put(p.getUniqueId(), future);
                NBTCompound nbt = new NBTCompound();

                NBTCompound frontText = new NBTCompound();
                NBTList<NBTCompound> messages = new NBTList<>(NBTType.COMPOUND);
                while (line.size() < 4) {
                    line.add(Map.entry(line.size(), ""));
                }
                line.stream().map(Map.Entry::getValue).forEach((v) -> {
                    NBTCompound tcompound = new NBTCompound();
                    tcompound.setTag("translate", new NBTString(v));
                    tcompound.setTag("fallback", new NBTString("notfound"));
                    messages.addTag(tcompound);
                });
                frontText.setTag("messages", messages);
                nbt.setTag("front_text", frontText);
                WrapperPlayServerBlockEntityData signData = new WrapperPlayServerBlockEntityData(loc, BlockEntityTypes.SIGN, nbt);
                WrapperPlayServerOpenSignEditor editor = new WrapperPlayServerOpenSignEditor(loc, true);
                WrapperPlayServerCloseWindow close = new WrapperPlayServerCloseWindow();
                PacketEvents.getAPI().getPlayerManager().sendPacket(p, signData);
                PacketEvents.getAPI().getPlayerManager().sendPacket(p, editor);
                PacketEvents.getAPI().getPlayerManager().sendPacket(p, close);
                return future.thenApply((result) -> {
                    ArrayList<Integer> arr = new ArrayList<>(func);
                    result.forEach((r) -> arr.add(finalShift + r));
                    return arr;
                });
            });
            shift+=4;
        }
        results.thenAccept((ignore) -> {
            WrapperPlayServerBlockChange ogBlockChange = new WrapperPlayServerBlockChange(loc, ogState);
            PacketEvents.getAPI().getPlayerManager().sendPacket(p, ogBlockChange);
            SignDoneListener.getFutures().remove(p.getUniqueId());
        });
        return results.completeOnTimeout(new ArrayList<>(), 2, TimeUnit.SECONDS);


    }

    public <T> List<List<T>> chunkList(List<T> originalList, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();

        for (int i = 0; i < originalList.size(); i += chunkSize) {
            int end = Math.min(originalList.size(), i + chunkSize);
            chunks.add(new ArrayList<>(originalList.subList(i, end)));
        }
        return chunks;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    //I'm terrible at naming classes.
    public record RealAndAlias(String real, String alias) {

    }
}
