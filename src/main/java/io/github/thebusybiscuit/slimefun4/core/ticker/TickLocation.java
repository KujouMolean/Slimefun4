package io.github.thebusybiscuit.slimefun4.core.ticker;

import io.github.bakedlibs.dough.blocks.BlockPosition;
import java.util.UUID;
import lombok.Getter;
import org.bukkit.Location;

@Getter
public class TickLocation {
    private final BlockPosition position;
    private final UUID uuid;

    public TickLocation(BlockPosition position) {
        this.position = position;
        uuid = null;
    }

    public TickLocation(BlockPosition position, UUID uuid) {
        this.position = position;
        this.uuid = uuid;
    }

    public boolean isUniversal() {
        return uuid != null;
    }

    public Location getLocation() {
        return position.toLocation();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof TickLocation other)) {
            return false;
        }

        return position.equals(other.position) && java.util.Objects.equals(uuid, other.uuid);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(position, uuid);
    }
}
