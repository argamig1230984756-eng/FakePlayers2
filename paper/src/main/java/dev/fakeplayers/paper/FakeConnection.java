package dev.fakeplayers.paper;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** A network connection that goes nowhere. */
public class FakeConnection extends Connection {
    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
    }
    public EmbeddedChannel embedded() { return (EmbeddedChannel) this.channel; }
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
}
