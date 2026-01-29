package dev.dmgiangi.core.server.domain.exception;

/**
 * Exception thrown when a resource (system, device, etc.) is not found.
 * Per Phase 5: Generic not-found exception for REST API.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;

    public ResourceNotFoundException(final String resourceType, final String resourceId) {
        super(String.format("%s not found: %s", resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public static ResourceNotFoundException system(final String systemId) {
        return new ResourceNotFoundException("System", systemId);
    }

    public static ResourceNotFoundException device(final String deviceId) {
        return new ResourceNotFoundException("Device", deviceId);
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }
}

