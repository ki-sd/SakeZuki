package com.sakezuki.backend.image;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationImageProjection {
    private final CatalogImageMapper images;
    private final ObjectMapper json;

    RecommendationImageProjection(CatalogImageMapper images, ObjectMapper json) {
        this.images = images;
        this.json = json;
    }

    public String currentImages(String snapshot) {
        JsonNode root = json.readTree(snapshot);
        var sakes = new ArrayList<ObjectNode>();
        collect(root.get("sake"), sakes);
        var recommendations = root.get("recommendations");
        if (recommendations != null && recommendations.isArray()) {
            for (JsonNode recommendation : recommendations) collect(recommendation, sakes);
        }
        if (sakes.isEmpty()) return snapshot;

        Map<Long, String> managed = new HashMap<>();
        var numbers = sakes.stream().map(sake -> sake.path("no").longValue()).distinct().toList();
        for (var image : images.managedImages(numbers)) managed.put(image.sakeNo(), image.imageUrl());
        boolean changed = false;
        for (var sake : sakes) {
            long no = sake.path("no").longValue();
            if (managed.containsKey(no) || sake.path("imageUrl").asString("").startsWith("/api/images/sakes/")) {
                sake.put("imageUrl", managed.get(no));
                changed = true;
            }
        }
        return changed ? json.writeValueAsString(root) : snapshot;
    }

    private static void collect(JsonNode node, List<ObjectNode> sakes) {
        if (node instanceof ObjectNode sake && sake.path("no").isIntegralNumber() && sake.path("no").canConvertToLong()) {
            sakes.add(sake);
        }
    }
}
