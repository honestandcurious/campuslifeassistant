package com.student.agent.service;

import com.student.agent.mcp.WeatherMcpClient;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    private final WeatherMcpClient weatherMcpClient;

    public WeatherService(WeatherMcpClient weatherMcpClient) {
        this.weatherMcpClient = weatherMcpClient;
    }

    public String getWeather(String query) {
        return weatherMcpClient.queryWeather(query);
    }
}
