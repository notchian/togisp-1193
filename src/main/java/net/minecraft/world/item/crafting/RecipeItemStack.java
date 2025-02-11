package net.minecraft.world.item.crafting;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparators;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.PacketDataSerializer;
import net.minecraft.resources.MinecraftKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ChatDeserializer;
import net.minecraft.world.entity.player.AutoRecipeStackManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.IMaterial;

public final class RecipeItemStack implements Predicate<ItemStack> {

    public static final RecipeItemStack EMPTY = new RecipeItemStack(Stream.empty());
    private final RecipeItemStack.Provider[] values;
    @Nullable
    public ItemStack[] itemStacks;
    @Nullable
    private IntList stackingIds;
    public boolean exact; // CraftBukkit

    public RecipeItemStack(Stream<? extends RecipeItemStack.Provider> stream) {
        this.values = (RecipeItemStack.Provider[]) stream.toArray((i) -> {
            return new RecipeItemStack.Provider[i];
        });
    }

    public ItemStack[] getItems() {
        if (this.itemStacks == null) {
            this.itemStacks = (ItemStack[]) Arrays.stream(this.values).flatMap((recipeitemstack_provider) -> {
                return recipeitemstack_provider.getItems().stream();
            }).distinct().toArray((i) -> {
                return new ItemStack[i];
            });
        }

        return this.itemStacks;
    }

    public boolean test(@Nullable ItemStack itemstack) {
        if (itemstack == null) {
            return false;
        } else if (this.isEmpty()) {
            return itemstack.isEmpty();
        } else {
            ItemStack[] aitemstack = this.getItems();
            int i = aitemstack.length;

            for (int j = 0; j < i; ++j) {
                ItemStack itemstack1 = aitemstack[j];

                // CraftBukkit start
                if (exact) {
                    if (itemstack1.getItem() == itemstack.getItem() && ItemStack.tagMatches(itemstack, itemstack1)) {
                        return true;
                    }

                    continue;
                }
                // CraftBukkit end
                if (itemstack1.is(itemstack.getItem())) {
                    return true;
                }
            }

            return false;
        }
    }

    public IntList getStackingIds() {
        if (this.stackingIds == null) {
            ItemStack[] aitemstack = this.getItems();

            this.stackingIds = new IntArrayList(aitemstack.length);
            ItemStack[] aitemstack1 = aitemstack;
            int i = aitemstack.length;

            for (int j = 0; j < i; ++j) {
                ItemStack itemstack = aitemstack1[j];

                this.stackingIds.add(AutoRecipeStackManager.getStackingIndex(itemstack));
            }

            this.stackingIds.sort(IntComparators.NATURAL_COMPARATOR);
        }

        return this.stackingIds;
    }

    public void toNetwork(PacketDataSerializer packetdataserializer) {
        packetdataserializer.writeCollection(Arrays.asList(this.getItems()), PacketDataSerializer::writeItem);
    }

    public JsonElement toJson() {
        if (this.values.length == 1) {
            return this.values[0].serialize();
        } else {
            JsonArray jsonarray = new JsonArray();
            RecipeItemStack.Provider[] arecipeitemstack_provider = this.values;
            int i = arecipeitemstack_provider.length;

            for (int j = 0; j < i; ++j) {
                RecipeItemStack.Provider recipeitemstack_provider = arecipeitemstack_provider[j];

                jsonarray.add(recipeitemstack_provider.serialize());
            }

            return jsonarray;
        }
    }

    public boolean isEmpty() {
        return this.values.length == 0;
    }

    private static RecipeItemStack fromValues(Stream<? extends RecipeItemStack.Provider> stream) {
        RecipeItemStack recipeitemstack = new RecipeItemStack(stream);

        return recipeitemstack.isEmpty() ? RecipeItemStack.EMPTY : recipeitemstack;
    }

    public static RecipeItemStack of() {
        return RecipeItemStack.EMPTY;
    }

    public static RecipeItemStack of(IMaterial... aimaterial) {
        return of(Arrays.stream(aimaterial).map(ItemStack::new));
    }

    public static RecipeItemStack of(ItemStack... aitemstack) {
        return of(Arrays.stream(aitemstack));
    }

    public static RecipeItemStack of(Stream<ItemStack> stream) {
        return fromValues(stream.filter((itemstack) -> {
            return !itemstack.isEmpty();
        }).map(RecipeItemStack.StackProvider::new));
    }

    public static RecipeItemStack of(TagKey<Item> tagkey) {
        return fromValues(Stream.of(new RecipeItemStack.b(tagkey)));
    }

    public static RecipeItemStack fromNetwork(PacketDataSerializer packetdataserializer) {
        return fromValues(packetdataserializer.readList(PacketDataSerializer::readItem).stream().map(RecipeItemStack.StackProvider::new));
    }

    public static RecipeItemStack fromJson(@Nullable JsonElement jsonelement) {
        if (jsonelement != null && !jsonelement.isJsonNull()) {
            if (jsonelement.isJsonObject()) {
                return fromValues(Stream.of(valueFromJson(jsonelement.getAsJsonObject())));
            } else if (jsonelement.isJsonArray()) {
                JsonArray jsonarray = jsonelement.getAsJsonArray();

                if (jsonarray.size() == 0) {
                    throw new JsonSyntaxException("Item array cannot be empty, at least one item must be defined");
                } else {
                    return fromValues(StreamSupport.stream(jsonarray.spliterator(), false).map((jsonelement1) -> {
                        return valueFromJson(ChatDeserializer.convertToJsonObject(jsonelement1, "item"));
                    }));
                }
            } else {
                throw new JsonSyntaxException("Expected item to be object or array of objects");
            }
        } else {
            throw new JsonSyntaxException("Item cannot be null");
        }
    }

    private static RecipeItemStack.Provider valueFromJson(JsonObject jsonobject) {
        if (jsonobject.has("item") && jsonobject.has("tag")) {
            throw new JsonParseException("An ingredient entry is either a tag or an item, not both");
        } else if (jsonobject.has("item")) {
            Item item = ShapedRecipes.itemFromJson(jsonobject);

            return new RecipeItemStack.StackProvider(new ItemStack(item));
        } else if (jsonobject.has("tag")) {
            MinecraftKey minecraftkey = new MinecraftKey(ChatDeserializer.getAsString(jsonobject, "tag"));
            TagKey<Item> tagkey = TagKey.create(Registries.ITEM, minecraftkey);

            return new RecipeItemStack.b(tagkey);
        } else {
            throw new JsonParseException("An ingredient entry needs either a tag or an item");
        }
    }

    public interface Provider {

        Collection<ItemStack> getItems();

        JsonObject serialize();
    }

    private static class b implements RecipeItemStack.Provider {

        private final TagKey<Item> tag;

        b(TagKey<Item> tagkey) {
            this.tag = tagkey;
        }

        @Override
        public Collection<ItemStack> getItems() {
            List<ItemStack> list = Lists.newArrayList();
            Iterator iterator = BuiltInRegistries.ITEM.getTagOrEmpty(this.tag).iterator();

            while (iterator.hasNext()) {
                Holder<Item> holder = (Holder) iterator.next();

                list.add(new ItemStack(holder));
            }

            return list;
        }

        @Override
        public JsonObject serialize() {
            JsonObject jsonobject = new JsonObject();

            jsonobject.addProperty("tag", this.tag.location().toString());
            return jsonobject;
        }
    }

    public static class StackProvider implements RecipeItemStack.Provider {

        private final ItemStack item;

        public StackProvider(ItemStack itemstack) {
            this.item = itemstack;
        }

        @Override
        public Collection<ItemStack> getItems() {
            return Collections.singleton(this.item);
        }

        @Override
        public JsonObject serialize() {
            JsonObject jsonobject = new JsonObject();

            jsonobject.addProperty("item", BuiltInRegistries.ITEM.getKey(this.item.getItem()).toString());
            return jsonobject;
        }
    }
}
