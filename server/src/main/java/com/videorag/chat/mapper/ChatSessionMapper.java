package com.videorag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videorag.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
