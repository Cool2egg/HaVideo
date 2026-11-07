package com.videorag.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videorag.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
