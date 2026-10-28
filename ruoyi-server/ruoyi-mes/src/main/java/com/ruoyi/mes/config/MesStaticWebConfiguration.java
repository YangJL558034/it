package com.ruoyi.mes.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
public class MesStaticWebConfiguration implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
      .addResourceHandler("/admin/**")
      .addResourceLocations("classpath:/static/admin/")
      .resourceChain(true)
      .addResolver(
        new PathResourceResolver() {
          @Override
          protected Resource getResource(String path, Resource location)
            throws IOException {
            Resource resolved = super.getResource(path, location);
            if (resolved != null) return resolved;
            if (
              !path.contains(".") && !path.contains("..")
            ) return super.getResource("index.html", location);
            return null;
          }
        }
      );
    registry
      .addResourceHandler("/assets/**")
      .addResourceLocations("classpath:/static/terminal/assets/");
    registry
      .addResourceHandler("/branding/**")
      .addResourceLocations("classpath:/static/terminal/branding/");
  }
}
