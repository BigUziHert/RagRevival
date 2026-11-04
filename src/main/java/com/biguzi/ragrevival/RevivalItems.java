package com.biguzi.ragrevival;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/** Shared eligibility for server validation, client input, and the compact item hint. */
public final class RevivalItems {
    public static final TagKey<Item> REVIVAL_ITEMS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("ragrevival", "revival_items"));

    private RevivalItems() {}

    public static boolean canRevive(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(REVIVAL_ITEMS)) return false;
        if (!(stack.getItem() instanceof PotionItem)) return true;
        return stack.is(Items.POTION) && hasRevivalPotionContents(stack);
    }

    public static boolean canSplashRevive(ItemStack stack) {
        return stack.is(Items.SPLASH_POTION) && hasRevivalPotionContents(stack);
    }

    private static boolean hasRevivalPotionContents(ItemStack stack) {
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        // Vanilla's match excludes custom effects, including mixtures with an eligible base.
        return contents.is(Potions.STRONG_HEALING) || contents.is(Potions.STRONG_REGENERATION);
    }

    /** One representative per item/potion family, rebuilt from the current synchronized tag. */
    public static List<ItemStack> examples() {
        return BuiltInRegistries.ITEM.getTag(REVIVAL_ITEMS)
                .map(items -> items.stream().flatMap(item -> {
                    if (item.value() == Items.POTION) {
                        return Stream.of(PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING),
                                PotionContents.createItemStack(Items.POTION, Potions.STRONG_REGENERATION));
                    }
                    return Stream.of(new ItemStack(item.value()));
                }).filter(RevivalItems::canRevive).toList())
                .orElse(List.of());
    }
}
