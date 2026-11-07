package com.videorag.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videorag.pipeline.entity.FailedTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FailedTaskMapper extends BaseMapper<FailedTask> {
}
