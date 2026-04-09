package com.student.agent.mcp;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class RouteMcpClient {

    private static final Logger log = LoggerFactory.getLogger(RouteMcpClient.class);

    private final RestClient.Builder restClientBuilder;

    @Value("${app.amap.base-url:https://restapi.amap.com}")
    private String baseUrl;

    @Value("${app.amap.api-key:}")
    private String apiKey;

    public RouteMcpClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public String queryRoute(String query) {
        String[] endpoints = extractRoute(query);
        String originName = endpoints[0];
        String destName = endpoints[1];

        if (!StringUtils.hasText(apiKey)) {
            return "缺少高德地图 API Key，无法查询真实路线。";
        }

        try {
            RestClient client = restClientBuilder.baseUrl(baseUrl).build();

            // 1. 【核心修复】调用 POI 搜索接口获取真实建筑物的经纬度和官方名称
            String[] originInfo = getPoiInfo(client, originName);
            String[] destInfo = getPoiInfo(client, destName);

            if (originInfo == null || destInfo == null) {
                return String.format("无法在高德地图上精准定位到【%s】或【%s】，请尝试提供更简洁、准确的地名（注意去掉'距离'、'怎么走'等多余字眼）。",
                        originInfo == null ? originName : originInfo[1],
                        destInfo == null ? destName : destInfo[1]);
            }

            // 获取坐标和高德官方的标准地名（标准地名返给大模型，防止幻觉）
            String originLocation = originInfo[0];
            String realOriginName = originInfo[1];
            String destLocation = destInfo[0];
            String realDestName = destInfo[1];

            // 2. 调用步行路线规划 API
            Map<String, Object> response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/v3/direction/walking")
                            .queryParam("key", apiKey)
                            .queryParam("origin", originLocation)
                            .queryParam("destination", destLocation)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            // 3. 解析并返回结果
            return parseRouteResponse(realOriginName, realDestName, response);

        } catch (Exception ex) {
            log.error("Route API failed: {}", ex.getMessage(), ex);
            return "调用高德地图服务异常，路线规划失败。";
        }
    }

    /**
     * 【核心修复一】使用 POI 关键字搜索 API (`/v3/place/text`)
     * 这个接口专门用于搜索“竹园食堂”、“科技路地铁站”等地名
     */
    private String[] getPoiInfo(RestClient client, String placeName) {
        try {
            Map<String, Object> response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/v3/place/text")
                            .queryParam("key", apiKey)
                            .queryParam("keywords", placeName)
                            .queryParam("offset", "1") // 只需要匹配度最高的第1条数据
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response != null && "1".equals(response.get("status"))) {
                List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
                if (pois != null && !pois.isEmpty()) {
                    Map<String, Object> firstPoi = pois.get(0);
                    String location = (String) firstPoi.get("location");
                    String name = (String) firstPoi.get("name");
                    // 返回匹配到的坐标，以及高德地图上这个地点的官方名称
                    return new String[]{location, name};
                }
            }
        } catch (Exception e) {
            log.error("Failed to search POI for keyword: {}", placeName, e);
        }
        return null;
    }

    /**
     * 解析高德步行路线结果
     */
    private String parseRouteResponse(String origin, String dest, Map<String, Object> response) {
        try {
            if (response != null && "1".equals(response.get("status"))) {
                Map<String, Object> route = (Map<String, Object>) response.get("route");
                List<Map<String, Object>> paths = (List<Map<String, Object>>) route.get("paths");

                if (paths != null && !paths.isEmpty()) {
                    Map<String, Object> path = paths.get(0);
                    int distance = Integer.parseInt((String) path.get("distance"));
                    int durationMinutes = Integer.parseInt((String) path.get("duration")) / 60;

                    return String.format("真实路线规划成功：起点匹配为【%s】，终点匹配为【%s】。步行距离约 %d 米，预计需要 %d 分钟。",
                            origin, dest, distance, durationMinutes);
                }
            } else {
                // 如果出现距离过远（超出100km等），把高德的真实报错原因告诉大模型
                String info = (String) response.get("info");
                return String.format("从【%s】到【%s】的路线无法规划。原因：%s（可能是距离过远超出了步行支持范围）。", origin, dest, info);
            }
        } catch (Exception e) {
            log.warn("Failed to parse AMap route response", e);
        }
        return "查询成功，但未能解析详细路线数据。";
    }

    /**
     * 【核心修复二】更强力的废话过滤
     */
    private String[] extractRoute(String query) {
        // 大幅增强对大模型/用户输入中干扰词的清洗，防止高德拿着“的步行距离”去搜索
        String cleanQuery = query.replaceAll("(怎么走|的?步行距离|路线|多远|在哪|需要多久|的?距离)", "").trim();

        if (cleanQuery.contains("到")) {
            String[] split = cleanQuery.split("到");
            if (split.length >= 2) {
                return new String[]{split[0].replace("从", "").trim(), split[1].trim()};
            }
        }
        return new String[]{"西安电子科技大学南校区", "竹园食堂"};
    }
}

//package com.student.agent.mcp;
//
//import java.util.Map;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.core.ParameterizedTypeReference;
//import org.springframework.stereotype.Component;
//import org.springframework.util.StringUtils;
//import org.springframework.web.client.RestClient;
//
//@Component
//public class RouteMcpClient {
//
//    private static final Logger log = LoggerFactory.getLogger(RouteMcpClient.class);
//
//    private final RestClient.Builder restClientBuilder;
//
//    @Value("${app.amap.base-url}")
//    private String baseUrl;
//
//    @Value("${app.amap.api-key:}")
//    private String apiKey;
//
//    public RouteMcpClient(RestClient.Builder restClientBuilder) {
//        this.restClientBuilder = restClientBuilder;
//    }
//
//    public String queryRoute(String query) {
//        String[] endpoints = extractRoute(query);
//        if (!StringUtils.hasText(apiKey)) {
//            return "路线规划（模拟结果）：从" + endpoints[0] + "到" + endpoints[1] + "，建议步行12分钟，如需真实导航请配置高德地图 API Key。";
//        }
//        try {
//            RestClient client = restClientBuilder.baseUrl(baseUrl).build();
//            Map<String, Object> response = client.get()
//                    .uri(uriBuilder -> uriBuilder.path("/v5/direction/walking")
//                            .queryParam("key", apiKey)
//                            .queryParam("origin", "121.4737,31.2304")
//                            .queryParam("destination", "121.4800,31.2350")
//                            .build())
//                    .retrieve()
//                    .body(new ParameterizedTypeReference<>() {
//                    });
//            return "路线规划结果：" + response;
//        } catch (Exception ex) {
//            log.warn("Route API failed, fallback to mock response: {}", ex.getMessage());
//            return "路线规划（降级结果）：从" + endpoints[0] + "到" + endpoints[1] + "，建议步行12分钟。";
//        }
//    }
//
//    private String[] extractRoute(String query) {
//        if (query.contains("到")) {
//            String[] split = query.replace("怎么走", "").replace("路线", "").split("到");
//            if (split.length >= 2) {
//                return new String[]{split[0].replace("从", "").trim(), split[1].trim()};
//            }
//        }
//        return new String[]{"图书馆", "实验楼"};
//    }
//}
