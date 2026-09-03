package io.github.jaehyeoksim.sourcing.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 크롬 확장은 chrome-extension:// 오리진에서 API 를 호출하므로 명시적으로 허용해야 한다.
 * 운영에서는 설치된 확장 ID 로 좁히는 것이 맞고, 여기서는 로컬 데모 기준으로 둔다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("chrome-extension://*", "http://localhost:*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }

    /**
     * 서버를 띄우면 바로 현황 화면이 보이게 한다.
     *
     * <p>정적 리소스 핸들러는 디렉터리 경로를 index.html 로 풀어주지 않는다(루트만 예외).
     * `/dashboard/` 로 들어와도 열리도록 포워딩을 명시한다.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/dashboard/");
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    }
}
