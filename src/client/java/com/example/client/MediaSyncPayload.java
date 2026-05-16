package com.example.client;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

public record MediaSyncPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MediaSyncPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modid", "media_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MediaSyncPayload> STREAM_CODEC = CustomPacketPayload.codec(MediaSyncPayload::write, MediaSyncPayload::read);
	private static final int MAX_JSON_LENGTH = 32767;

	private static MediaSyncPayload read(RegistryFriendlyByteBuf buf) {
		int readable = buf.readableBytes();
		if (readable <= 0) {
			return new MediaSyncPayload("");
		}

		byte[] bytes = new byte[readable];
		buf.readBytes(bytes);
		return new MediaSyncPayload(new String(bytes, StandardCharsets.UTF_8));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		byte[] bytes = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_JSON_LENGTH) {
			throw new IllegalArgumentException("MediaSync payload too large: " + bytes.length + " bytes");
		}
		buf.writeBytes(bytes);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
