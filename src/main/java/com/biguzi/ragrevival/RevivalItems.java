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
        if (!stack.is(Items.POTION)) return false;
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        // A potion item tag cannot distinguish its contents. Vanilla's match also excludes
        // custom effects, so adding a healing base cannot disguise a harmful mixture.
        return contents.is(Potions.HEALING) || contents.is(Potions.STRONG_HEALING)
                || contents.is(Potions.REGENERATION) || contents.is(Potions.LONG_REGENERATION)
                || contents.is(Potions.STRONG_REGENERATION);
    }

    /** One representative per item/potion family, rebuilt from the current synchronized tag. */
    public static List<ItemStack> examples() {
        return BuiltInRegistries.ITEM.getTag(REVIVAL_ITEMS)
                .map(items -> items.stream().flatMap(item -> {
                    if (item.value() == Items.POTION) {
                        return Stream.of(PotionContents.createItemStack(Items.POTION, Potions.HEALING),
                                PotionContents.createItemStack(Items.POTION, Potions.REGENERATION));
                    }
                    return Stream.of(new ItemStack(item.value()));
                }).filter(RevivalItems::canRevive).toList())
                .orElse(List.of());
    }
}
