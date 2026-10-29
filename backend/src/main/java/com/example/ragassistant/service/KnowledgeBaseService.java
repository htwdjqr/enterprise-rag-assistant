package com.example.ragassistant.service;

import com.example.ragassistant.domain.CurrentUser;
import com.example.ragassistant.domain.KnowledgeBase;
import com.example.ragassistant.repository.KnowledgeBaseRepository;
import com.example.ragassistant.repository.UserRepository;
import com.example.ragassistant.web.BusinessException;
import com.example.ragassistant.web.ApiModels.Member;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 权限规则集中在此服务，上传、下载、问答等入口都复用它。 */
@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final UserRepository users;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, UserRepository users) {
        this.knowledgeBases = knowledgeBases;
        this.users = users;
    }

    @Transactional
    public KnowledgeBase create(CurrentUser user, String name, String description) {
        String id = UUID.randomUUID().toString();
        knowledgeBases.insert(id, user, name.strip(), description.strip());
        return requireRead(id, user);
    }

    public List<KnowledgeBase> list(CurrentUser user) { return knowledgeBases.listReadable(user); }

    public KnowledgeBase requireRead(String id, CurrentUser user) {
        return knowledgeBases.findReadable(id, user).orElseThrow(BusinessException::notFound);
    }

    public KnowledgeBase requireManage(String id, CurrentUser user) {
        KnowledgeBase kb = requireRead(id, user);
        if (!kb.ownerId().equals(user.id())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有知识库创建人可以执行管理操作");
        }
        return kb;
    }

    @Transactional
    public void grant(String id, String username, CurrentUser user) {
        requireManage(id, user);
        CurrentUser target = users.findByUsername(username)
                .filter(u -> u.tenantId().equals(user.tenantId()) && u.enabled())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "本租户内未找到该用户"));
        knowledgeBases.grant(id, target.id());
    }

    public List<Member> listMembers(String id, CurrentUser user) {
        requireManage(id, user);
        return knowledgeBases.listMembers(id, user.tenantId());
    }

    @Transactional
    public void revoke(String id, String username, CurrentUser user) {
        requireManage(id, user);
        users.findByUsername(username).filter(u -> u.tenantId().equals(user.tenantId()))
                .ifPresent(target -> knowledgeBases.revoke(id, target.id()));
    }
}
