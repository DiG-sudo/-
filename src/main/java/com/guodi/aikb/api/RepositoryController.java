package com.guodi.aikb.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.guodi.aikb.ai.tool.catalog.ToolCatalogService;
import com.guodi.aikb.ai.tool.catalog.ToolDescriptor;
import com.guodi.aikb.workspace.service.RepositoryOverviewService;

@RestController
@RequestMapping("/api/repository")
public class RepositoryController {

    private final RepositoryOverviewService overviewService;
    private final ToolCatalogService toolCatalogService;

    public RepositoryController(
            RepositoryOverviewService overviewService,
            ToolCatalogService toolCatalogService) {
        this.overviewService = overviewService;
        this.toolCatalogService = toolCatalogService;
    }

    @GetMapping("/overview")
    public Map<String, String> overview() {
        return Map.of("overview", overviewService.getOverview());
    }

    @PostMapping("/overview/refresh")
    public Map<String, String> refreshOverview() {
        return Map.of("overview", overviewService.refreshOverview());
    }

    @GetMapping("/tools")
    public List<ToolDescriptor> tools() {
        return toolCatalogService.listTools();
    }
}
