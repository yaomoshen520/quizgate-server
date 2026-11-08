package com.sex.antiaddiction.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 前端产物只按"具体文件名"给，目录形状的请求一律不回。
 * <p>
 * Spring 自己的 "/**" 静态映射会把目录当资源解析，两种运行形状各错一半：
 * JVM 上读目录抛异常，被接口层的兜底接住变成 500；GraalVM 原生二进制里
 * classpath 目录节点能读出一个"每行一个文件名"的清单，于是 GET /assets/
 * 直接 200 回给任何人——家长后台的 js/css 名字全泄露，而那些 hash 名字本身就是
 * 内部构建信息。这里只接管 "/assets/**"（比 "/**" 更具体的模式会先命中），
 * "/"、"/index.html"、"/favicon.ico" 仍旧走原来的映射，欢迎页与放行名单都不动。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .resourceChain(false)
                .addResolver(new FileOnlyResolver());
    }

    /**
     * 判定只看请求最后一段的形状，不看解析出来的资源——原生镜像里那个目录资源
     * 和真文件在接口上分不出来。构建产物每一个都带扩展名，所以"最后一段没有点"
     * 就是目录；以点开头的名字（.env、.git）一并拒掉，它们本来也不该被取。
     */
    static final class FileOnlyResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            String name = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (name.isEmpty() || name.indexOf('.') < 0 || name.charAt(0) == '.') {
                return null;
            }
            return super.getResource(resourcePath, location);
        }
    }
}
