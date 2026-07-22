package com.raindrop.storage;

public class ConnectionProfile {
    private long id;
    private String name;
    private String host;
    private int port = 22;
    private String authType = "credential";
    private Long credentialId;
    private String username;
    private String password;
    private String keyPath;
    private String keyPass;
    private String groupName = "默认";
    private String encoding = "UTF-8";
    private String createdAt;
    private String updatedAt;

    public ConnectionProfile() {}

    public ConnectionProfile(String name, String host, int port, String username) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }

    public String getKeyPass() { return keyPass; }
    public void setKeyPass(String keyPass) { this.keyPass = keyPass; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
