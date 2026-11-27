package com.sakezuki.backend.image;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RecommendationImageProjection {
    private final ImageAssetRepository assets;
    private final ObjectMapper json;

    RecommendationImageProjection(ImageAssetRepository assets, ObjectMapper json) {
        this.assets = assets;
        this.json = json;
    }

    public String currentImages(String snapshot) {
        JsonNode root = json.readTree(snapshot);
        boolean changed = project(root.get("sake"));
        var recommendations = root.get("recommendations");
        if (recommendations != null && recommendations.isArray()) {
            for (JsonNode recommendation : recommendations) changed |= project(recommendation);
        }
        return changed ? json.writeValueAsString(root) : snapshot;
    }

    private boolean project(JsonNode node) {
        if (!(node instanceof ObjectNode sake) || !sake.path("no").isIntegralNumber() || !sake.path("no").canConvertToLong()) {
            return false;
        }
        var current = assets.catalogImage(sake.path("no").longValue());
        String oldUrl = sake.path("imageUrl").asString("");
        if (current.isPresent() && current.get().hasManagedHistory()) {
            sake.put("imageUrl", current.get().managedUrl());
            return true;
        }
        if (oldUrl.startsWith("/api/images/sakes/")) {
            sake.putNull("imageUrl");
            return true;
        }
        return false;
    }
}
