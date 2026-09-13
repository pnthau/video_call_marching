package com.example.videocall_marching_language.template;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.IWebApplication;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebRequest;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserListTemplateTests {

    private SpringTemplateEngine templateEngine;

    @BeforeEach
    void setUp() throws Exception {
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(new ClassPathResource("templates/").getFile().toPath().toString().replace('\\', '/') + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCacheable(false);
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    void nonEmptyListRendersNarrowAdminUserActionsAndSearch() throws Exception {
        String html = render(List.of(User.builder().id(42L).username("alice").email("alice@example.com")
                .currentLevel(JapaneseLevel.N3).build()), "alice", "alice@example.com", 3, 1);

        assertTrue(html.contains("alice"));
        assertTrue(html.contains("alice@example.com"));
        assertTrue(html.contains("N3"));
        assertTrue(html.contains("href=\"/admin/users/edit/42\""));
        assertFalse(html.contains("/admin/users/42/edit"));
        assertEquals(1, countOccurrences(html, "name=\"username\""));
        assertEquals(1, countOccurrences(html, "name=\"email\""));
        assertFalse(html.contains("name=\"search\""));
        assertTrue(html.contains("value=\"alice\""));
        assertTrue(html.contains("value=\"alice@example.com\""));
        assertFalse(html.contains("Trust Score"));
        assertFalse(html.contains("trustScore"));
        assertFalse(html.contains("/admin/users/add"));
        assertFalse(html.contains("/admin/users/delete"));
        assertTrue(html.contains("page=0&amp;username=alice&amp;email=alice@example.com"));
        assertTrue(html.contains("page=1&amp;username=alice&amp;email=alice@example.com"));
        assertTrue(html.contains("page=2&amp;username=alice&amp;email=alice@example.com"));
    }

    @Test
    void emptyListRendersEmptyStateWithoutActions() throws Exception {
        String html = render(List.of(), "", "", 1, 0);

        assertTrue(html.contains("Khong tim thay nguoi dung nao phu hop"));
        assertFalse(html.contains("trustScore"));
        assertFalse(html.contains("/admin/users/add"));
        assertFalse(html.contains("/admin/users/delete"));
    }

    private String render(List<User> users, String username, String email, int totalPages, int page) throws Exception {
        WebContext context = new WebContext(exchange());
        context.setVariable("users", new org.springframework.data.domain.PageImpl<>(users,
                org.springframework.data.domain.PageRequest.of(page, 1), totalPages));
        context.setVariable("username", username);
        context.setVariable("email", email);
        return templateEngine.process("admin/users/list", context);
    }

    private int countOccurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }

    private IWebExchange exchange() {
        Map<String, Object> attributes = new java.util.HashMap<>();
        return new IWebExchange() {
            @Override public IWebRequest getRequest() { return request(); }
            @Override public org.thymeleaf.web.IWebSession getSession() { return null; }
            @Override public IWebApplication getApplication() { return application(); }
            @Override public java.security.Principal getPrincipal() { return null; }
            @Override public Locale getLocale() { return Locale.getDefault(); }
            @Override public String getContentType() { return "text/html"; }
            @Override public String getCharacterEncoding() { return "UTF-8"; }
            @Override public boolean containsAttribute(String name) { return attributes.containsKey(name); }
            @Override public int getAttributeCount() { return attributes.size(); }
            @Override public Set<String> getAllAttributeNames() { return attributes.keySet(); }
            @Override public Map<String, Object> getAttributeMap() { return attributes; }
            @Override public Object getAttributeValue(String name) { return attributes.get(name); }
            @Override public void setAttributeValue(String name, Object value) { attributes.put(name, value); }
            @Override public void removeAttribute(String name) { attributes.remove(name); }
            @Override public String transformURL(String url) { return url; }
        };
    }

    private IWebRequest request() {
        return new IWebRequest() {
            @Override public String getMethod() { return "GET"; }
            @Override public String getScheme() { return "http"; }
            @Override public String getServerName() { return "localhost"; }
            @Override public Integer getServerPort() { return 80; }
            @Override public String getApplicationPath() { return ""; }
            @Override public String getPathWithinApplication() { return "/admin/users"; }
            @Override public String getQueryString() { return null; }
            @Override public boolean containsHeader(String name) { return false; }
            @Override public int getHeaderCount() { return 0; }
            @Override public Set<String> getAllHeaderNames() { return Set.of(); }
            @Override public Map<String, String[]> getHeaderMap() { return Map.of(); }
            @Override public String[] getHeaderValues(String name) { return null; }
            @Override public boolean containsParameter(String name) { return false; }
            @Override public int getParameterCount() { return 0; }
            @Override public Set<String> getAllParameterNames() { return Set.of(); }
            @Override public Map<String, String[]> getParameterMap() { return Map.of(); }
            @Override public String[] getParameterValues(String name) { return null; }
            @Override public boolean containsCookie(String name) { return false; }
            @Override public int getCookieCount() { return 0; }
            @Override public Set<String> getAllCookieNames() { return Set.of(); }
            @Override public Map<String, String[]> getCookieMap() { return Map.of(); }
            @Override public String[] getCookieValues(String name) { return null; }
        };
    }

    private IWebApplication application() {
        return new IWebApplication() {
            @Override public boolean containsAttribute(String name) { return false; }
            @Override public int getAttributeCount() { return 0; }
            @Override public Set<String> getAllAttributeNames() { return Set.of(); }
            @Override public Map<String, Object> getAttributeMap() { return Map.of(); }
            @Override public Object getAttributeValue(String name) { return null; }
            @Override public void setAttributeValue(String name, Object value) { }
            @Override public void removeAttribute(String name) { }
            @Override public boolean resourceExists(String template) { return true; }
            @Override public java.io.InputStream getResourceAsStream(String template) { return null; }
        };
    }
}
