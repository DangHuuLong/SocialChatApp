package server;

import java.util.Set;
import java.util.concurrent.*;

public class RoomRegistry {
    // room_id -> tập các client online trong phòng
    private final ConcurrentMap<Long, CopyOnWriteArraySet<ClientHandler>> byId = new ConcurrentHashMap<>();
    // cache tên -> id để giảm truy vấn DB
    private final ConcurrentMap<String, Long> nameToId = new ConcurrentHashMap<>();

    public void cache(String roomName, long roomId){ nameToId.putIfAbsent(roomName, roomId); }
    public Long cachedId(String roomName){ return nameToId.get(roomName); }

    public void join(long roomId, ClientHandler h) {
        byId.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(h);
    }
    public void leave(long roomId, ClientHandler h) {
        byId.computeIfPresent(roomId, (k,set)-> { set.remove(h); return set.isEmpty()? null : set; });
    }
    public Set<ClientHandler> members(long roomId) {
        return byId.getOrDefault(roomId, new CopyOnWriteArraySet<>());
    }
    public void removeEverywhere(ClientHandler h){
        byId.values().forEach(set -> set.remove(h));
        byId.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
