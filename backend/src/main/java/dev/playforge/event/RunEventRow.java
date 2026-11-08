package dev.playforge.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** Persistence shape of {@link RunEvent}; payload stored as JSON text. */
@Data
@TableName("run_events")
public class RunEventRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long seq;
    private Instant ts;
    private RunEventType type;
    private String source;
    private String payloadJson;
}
