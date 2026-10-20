package ru.example.ultraenchant;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class UltraEnchantMod implements ModInitializer {
    // Maximum real projectiles per volley. Enchantment level can still be 10000.
    private static final int MAX_REAL_SHOTS = 33;
    private static final String SPAWN_MARKER = "ultraenchant:spawn_processed";

    private record RecentUse(int level, long tick) { }
    private static final Map<UUID, RecentUse> RECENT_USES = new HashMap<>();
    private static final Map<UUID, Long> LAST_VOLLEYS = new HashMap<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("ue")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("add")
                    .then(argument("enchantment", IdentifierArgumentType.identifier())
                        .then(argument("level", IntegerArgumentType.integer(1, 10000))
                            .executes(context -> addSingle(
                                context.getSource(),
                                IdentifierArgumentType.getIdentifier(context, "enchantment"),
                                IntegerArgumentType.getInteger(context, "level")
                            )))))
                .then(literal("all")
                    .then(argument("level", IntegerArgumentType.integer(1, 10000))
                        .executes(context -> addAll(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "level")
                        ))))
                .then(literal("clear")
                    .executes(context -> clear(context.getSource()))));
        });

        // Remember enchantments before throwable items decrement/disappear from the hand.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!world.isClient && !player.isSpectator()) {
                int level = EnchantmentHelper.getLevel(Enchantments.MULTISHOT, stack);
                if (level > 0) {
                    RECENT_USES.put(player.getUuid(), new RecentUse(level, world.getTime()));
                }
            }
            return TypedActionResult.pass(stack);
        });

        // On projectile spawn duplicate it in a fan. This also covers one-item stacks.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> onEntitySpawn(entity, world));
    }

    private static ItemStack held(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return source.getPlayerOrThrow().getMainHandStack();
    }

    private static String enchantmentTag(ItemStack stack) {
        return stack.isOf(Items.ENCHANTED_BOOK) ? "StoredEnchantments" : "Enchantments";
    }

    private static void setEnchantment(ItemStack stack, Identifier id, int level) {
        String key = enchantmentTag(stack);
        NbtCompound root = stack.getOrCreateNbt();
        NbtList entries = root.getList(key, NbtElement.COMPOUND_TYPE);
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            NbtCompound enchantment = entries.getCompound(i);
            if (enchantment.getString("id").equals(id.toString())) {
                enchantment.putShort("lvl", (short) level);
                found = true;
                break;
            }
        }
        if (!found) {
            NbtCompound enchantment = new NbtCompound();
            enchantment.putString("id", id.toString());
            enchantment.putShort("lvl", (short) level);
            entries.add(enchantment);
        }
        root.put(key, entries);
    }

    private static int addSingle(ServerCommandSource source, Identifier id, int level)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) {
            source.sendError(Text.literal("Возьми предмет в основную руку."));
            return 0;
        }
        if (!Registries.ENCHANTMENT.containsId(id)) {
            source.sendError(Text.literal("Неизвестное зачарование: " + id));
            return 0;
        }
        setEnchantment(stack, id, level);
        source.sendFeedback(() -> Text.literal("Готово: " + id + " " + level), false);
        return 1;
    }

    private static int addAll(ServerCommandSource source, int level)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) {
            source.sendError(Text.literal("Возьми предмет в основную руку."));
            return 0;
        }
        int[] count = {0};
        Registries.ENCHANTMENT.getIds().forEach(id -> {
            setEnchantment(stack, id, level);
            count[0]++;
        });
        source.sendFeedback(() -> Text.literal("Наложено чар: " + count[0] + ", уровень: " + level), false);
        return count[0];
    }

    private static int clear(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = held(source);
        if (stack.isEmpty()) return 0;
        if (stack.getNbt() != null) {
            stack.getNbt().remove("Enchantments");
            stack.getNbt().remove("StoredEnchantments");
        }
        source.sendFeedback(() -> Text.literal("Зачарования удалены с предмета."), false);
        return 1;
    }

    private static void onEntitySpawn(Entity entity, ServerWorld world) {
        if (!(entity instanceof ProjectileEntity projectile)) return;
        if (!(entity instanceof ThrownItemEntity || entity instanceof PersistentProjectileEntity)) return;
        if (!(projectile.getOwner() instanceof PlayerEntity shooter)) return;
        if (entity.getCommandTags().contains(SPAWN_MARKER)) return;
        entity.addCommandTag(SPAWN_MARKER);

        int level = Math.max(
            EnchantmentHelper.getLevel(Enchantments.MULTISHOT, shooter.getMainHandStack()),
            EnchantmentHelper.getLevel(Enchantments.MULTISHOT, shooter.getOffHandStack())
        );
        RecentUse recent = RECENT_USES.get(shooter.getUuid());
        if (recent != null && recent.tick() >= world.getTime() - 1) {
            level = Math.max(level, recent.level());
        }
        if (level == 0) return;

        // The first vanilla projectile per player per tick is expanded, not all three from crossbows.
        Long last = LAST_VOLLEYS.put(shooter.getUuid(), world.getTime());
        if (last != null && last == world.getTime()) return;

        int shotCount = Math.min(MAX_REAL_SHOTS, Math.max(3, 1 + level * 2));
        Vec3d originalVelocity = projectile.getVelocity();
        if (originalVelocity.lengthSquared() < 0.00001) return;

        NbtCompound template = entity.writeNbt(new NbtCompound());
        for (int i = 0; i < shotCount - 1; i++) {
            Entity copy = entity.getType().create(world);
            if (!(copy instanceof ProjectileEntity extra)) continue;

            copy.readNbt(template.copy());
            copy.setUuid(UUID.randomUUID());
            copy.addCommandTag(SPAWN_MARKER); // recursive-spawn protection

            float fraction = (i + 1.0f) / shotCount;
            float degrees = -27.0f + 54.0f * fraction;
            Vec3d velocity = originalVelocity.rotateY((float) Math.toRadians(degrees));
            copy.refreshPositionAndAngles(entity.getX(), entity.getY(), entity.getZ(),
                entity.getYaw() + degrees, entity.getPitch());
            extra.setOwner(shooter);
            extra.setVelocity(velocity.x, velocity.y, velocity.z);
            world.spawnEntity(extra);
        }
    }
}
