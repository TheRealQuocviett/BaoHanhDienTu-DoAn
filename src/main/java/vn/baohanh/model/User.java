package vn.baohanh.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Người dùng đang đăng nhập. */
public record User(long id, String username, String fullName, Role role) {

    public boolean is(Role... roles) {
        for (Role r : roles) if (r == role) return true;
        return false;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        m.put("fullName", fullName);
        m.put("role", role.name());
        m.put("roleLabel", role.getLabel());
        return m;
    }
}
