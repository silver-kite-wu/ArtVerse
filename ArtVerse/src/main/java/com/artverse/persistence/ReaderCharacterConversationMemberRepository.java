package com.artverse.persistence;

import com.artverse.domain.ReaderCharacterConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaderCharacterConversationMemberRepository extends JpaRepository<ReaderCharacterConversationMember, Long> {
    List<ReaderCharacterConversationMember> findByConversationIdAndLeftAtIsNullOrderByJoinedAtAsc(Long conversationId);
}
