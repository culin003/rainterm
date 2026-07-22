package com.raindrop.credential;

public class CredentialEntry {
    private long id;
    private String name;
    private String type;
    private String username;
    private String password;
    private String keyData;
    private String keyPath;
    private String keyPass;
    private String createdAt;
    private String updatedAt;

    public CredentialEntry() {}

    public CredentialEntry(String name, String type, String username) {
        this.name = name;
        this.type = type;
        this.username = username;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getKeyData() { return keyData; }
    public void setKeyData(String keyData) { this.keyData = keyData; }

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }

    public String getKeyPass() { return keyPass; }
    public void setKeyPass(String keyPass) { this.keyPass = keyPass; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return name + " (" + username + "@" + type + ")";
    }
}
