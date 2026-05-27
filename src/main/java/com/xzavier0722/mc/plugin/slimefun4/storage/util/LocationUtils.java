package com.xzavier0722.mc.plugin.slimefun4.storage.util;

import city.norain.slimefun4.SlimefunExtended;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

public class LocationUtils {
    /**
     * 将 {@link Location} 转换为数据库中使用的
     * 标准格式位置信息
     *
     * @param l {@link Location}
     * @return 标准化后的位置信息字符串
     */
    public static String getLocKey(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    /**
     * 将 {@link Chunk} 转换为数据库中使用的
     * 标准格式区块信息
     *
     * @param chunk {@link Chunk}
     * @return 标准化后的区块信息字符串
     */
    public static String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ";" + chunk.getX() + ":" + chunk.getZ();
    }

    public static String getChunkKey(Location loc) {
        return loc.getWorld().getName() + ";" + (loc.getBlockX() >> 4) + ":" + (loc.getBlockZ() >> 4);
    }

    public static Location toLocation(String lKey) {
        if (lKey == null || lKey.isEmpty()) {
            return null;
        }

        try {
            int semicolon = lKey.indexOf(';');
            int colon1 = lKey.indexOf(':', semicolon + 1);
            int colon2 = lKey.indexOf(':', colon1 + 1);

            return new Location(
                    Bukkit.getWorld(lKey.substring(0, semicolon)),
                    Integer.parseInt(lKey.substring(semicolon + 1, colon1)),
                    Integer.parseInt(lKey.substring(colon1 + 1, colon2)),
                    Integer.parseInt(lKey.substring(colon2 + 1)));
        } catch (Exception e) {
            throw new RuntimeException("Unable to parse location [" + lKey + "]", e);
        }
    }

    public static boolean isSameChunk(Chunk c1, Chunk c2) {
        return c1 == c2
                || (isSameWorld(c1.getWorld(), c2.getWorld()) && c1.getX() == c2.getX() && c1.getZ() == c2.getZ());
    }

    public static boolean isSameChunk(Location l1, Location l2) {
        return l1 == l2
                || (isSameWorld(l1.getWorld(), l2.getWorld())
                        && (l1.getBlockX() >> 4) == (l2.getBlockX() >> 4)
                        && (l1.getBlockZ() >> 4) == (l2.getBlockZ() >> 4));
    }

    public static boolean isSameLoc(Location l1, Location l2) {
        return l1 == l2
                || (isSameWorld(l1.getWorld(), l2.getWorld())
                        && l1.getBlockX() == l2.getBlockX()
                        && l1.getBlockY() == l2.getBlockY()
                        && l1.getBlockZ() == l2.getBlockZ());
    }

    public static Chunk toChunk(World w, String cKey) {
        int semicolon = cKey.indexOf(';');
        int colon = cKey.indexOf(':', semicolon + 1);
        int chunkX = Integer.parseInt(cKey.substring(semicolon + 1, colon));
        int chunkZ = Integer.parseInt(cKey.substring(colon + 1));
        if (SlimefunExtended.isAtLeast(1, 19, 4)) {
            return w.getChunkAt(chunkX, chunkZ, false);
        } else {
            return w.getChunkAt(chunkX, chunkZ);
        }
    }

    public static boolean isSameWorld(World w1, World w2) {
        return w1 == w2 || w1.getUID().equals(w2.getUID());
    }

    /**
     * 将位置转换为易读的文本
     *
     * 注意: 请不要将其用于数据库转换过程中!
     *
     * @param location 位置
     * @return 易读的位置文本
     */
    public static String locationToString(Location location) {
        if (location == null) {
            return "null";
        }

        return "[world="
                + location.getWorld().getName()
                + ",x="
                + location.getX()
                + ",y="
                + location.getY()
                + ",z="
                + location.getZ()
                + "]";
    }
}
