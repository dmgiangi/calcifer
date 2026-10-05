package tech.calcifer.ragequit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StaticUiResourceTest {
    private static final Path DIRECTORY = temporaryDirectory();

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("rage-quit.issuer", () -> "https://auth.calcifer.tech");
        properties.add("rage-quit.client-id", () -> "rage-quit");
        properties.add("rage-quit.client-secret", () -> "synthetic-ui-test-fixture");
        properties.add("rage-quit.database", () -> DIRECTORY.resolve("ui.sqlite").toString());
        properties.add("rage-quit.start-date", () -> "2026-01-01");
    }

    @AfterAll
    static void removeTemporaryDatabase() throws IOException {
        try (var files = Files.walk(DIRECTORY)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test
    void loginAndItsStylesheetArePublicAndDashboardAssetsAreServedToAnAuthenticatedParticipant() throws Exception {
        mvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continua con Google")));
        mvc.perform(get("/style.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "css")));

        String dashboard = mvc.perform(get("/index.html").with(user("synthetic-participant")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("/app.js", "/style.css", "lang=\"it\"");

        String script = mvc.perform(get("/app.js").with(user("synthetic-participant")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(script).contains("/api/session", "csrfHeader", "Europe/Rome");

        String stylesheet = mvc.perform(get("/style.css").with(user("synthetic-participant")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(stylesheet).contains("@media (min-width: 720px)", ":focus-visible");
    }

    @Test
    void dashboardAndItsScriptRemainProtected() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/app.js")).andExpect(status().is3xxRedirection());
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("rage-quit-static-ui-"); }
        catch (IOException error) { throw new ExceptionInInitializerError("Cannot create disposable UI test storage"); }
    }
}
