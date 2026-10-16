package dev.kivts.cide.net.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record FileListPayload(int computerId, String path, List<Entry> entries, boolean truncated, long freeSpace) {

    public static final ResourceLocation TYPE = new ResourceLocation("ccstudio", "file_list");

    public static void encode(FileListPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.computerId);
        buf.writeUtf(p.path);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUtf(e.path);
            buf.writeBoolean(e.directory);
            buf.writeVarLong(e.size);
            buf.writeBoolean(e.readOnly);
        }
        buf.writeBoolean(p.truncated);
        buf.writeVarLong(p.freeSpace);
    }

    public static FileListPayload decode(FriendlyByteBuf buf) {
        int computerId = buf.readVarInt();
        String path = buf.readUtf();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readUtf(), buf.readBoolean(), buf.readVarLong(), buf.readBoolean()));
        }
        boolean truncated = buf.readBoolean();
        long freeSpace = buf.readVarLong();
        return new FileListPayload(computerId, path, entries, truncated, freeSpace);
    }

    public record Entry(String path, boolean directory, long size, boolean readOnly) {}
}
