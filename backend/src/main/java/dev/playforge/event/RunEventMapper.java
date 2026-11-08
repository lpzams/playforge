package dev.playforge.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RunEventMapper extends BaseMapper<RunEventRow> {
    @Select("SELECT COALESCE(MAX(seq), 0) FROM run_events WHERE run_id = #{runId}")
    long maxSeq(@Param("runId") long runId);
}
