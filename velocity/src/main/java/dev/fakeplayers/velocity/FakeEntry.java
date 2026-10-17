package dev.fakeplayers.velocity;

import java.util.UUID;

/** Proxy-side record of a fake player living on some backend server. */
public class FakeEntry {
    public String server;
    public UUID uuid;
    public String name, display = "", skinValue = "", skinSignature = "";
    public int ping, gamemode;
    public boolean listed = true;
    public long epoch;
}
