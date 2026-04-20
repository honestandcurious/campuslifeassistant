package com.student.agent.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    @Bean(destroyMethod = "close")
    public McpClient amapMcpClient(@Value("${app.amap.api-key}") String amapApiKey) {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("npx.cmd", "-y", "@amap/amap-maps-mcp-server"))
                .environment(Map.of("AMAP_MAPS_API_KEY", amapApiKey))
                .logEvents(true)
                .build();

        return new DefaultMcpClient.Builder()
                .key("amap-maps")
                .transport(transport)
                .build();
    }

    @Bean(destroyMethod = "close")
    public McpClient openMeteoMcpClient() {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("npx.cmd", "open-meteo-mcp"))
                .logEvents(true)
                .build();

        return new DefaultMcpClient.Builder()
                .key("open-meteo-mcp")
                .transport(transport)
                .build();
    }

    @Bean
    public ToolProvider mcpToolProvider(McpClient amapMcpClient, McpClient openMeteoMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(amapMcpClient, openMeteoMcpClient)
                .failIfOneServerFails(true)
                .build();
    }

    @Bean
    public CommandLineRunner mcpToolLogger(McpClient amapMcpClient, McpClient openMeteoMcpClient) {
        return args -> {
            log.info("AMap MCP tools: {}", amapMcpClient.listTools());
            log.info("OpenMeteo MCP tools: {}", openMeteoMcpClient.listTools());
        };
    }
}
