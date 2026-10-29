package com.example.ragassistant.web;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.domain.KnowledgeBase;
import com.example.ragassistant.service.KnowledgeBaseService;
import com.example.ragassistant.service.QuestionAnswerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Controller 只处理 HTTP 与参数校验，业务和权限逻辑放在 Service。 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBases;
    private final QuestionAnswerService questions;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases, QuestionAnswerService questions) {
        this.knowledgeBases = knowledgeBases;
        this.questions = questions;
    }

    @GetMapping
    public List<KnowledgeBase> list(@AuthenticationPrincipal CurrentUser user) { return knowledgeBases.list(user); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@Valid @RequestBody ApiModels.CreateKnowledgeBase request,
                                @AuthenticationPrincipal CurrentUser user) {
        return knowledgeBases.create(user, request.name(), request.description() == null ? "" : request.description());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable String id, @Valid @RequestBody ApiModels.GrantMember request,
                      @AuthenticationPrincipal CurrentUser user) {
        knowledgeBases.grant(id, request.username(), user);
    }

    @GetMapping("/{id}/members")
    public List<ApiModels.Member> members(@PathVariable String id, @AuthenticationPrincipal CurrentUser user) {
        return knowledgeBases.listMembers(id, user);
    }

    @DeleteMapping("/{id}/members/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, @PathVariable String username,
                       @AuthenticationPrincipal CurrentUser user) { knowledgeBases.revoke(id, username, user); }

    @PostMapping("/{id}/questions")
    public ApiModels.AskResponse ask(@PathVariable String id, @Valid @RequestBody ApiModels.AskRequest request,
                                    @AuthenticationPrincipal CurrentUser user) {
        return questions.ask(id, request.question().strip(), user);
    }
}
