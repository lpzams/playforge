package dev.playforge.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("runs")
public class Run {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectId;
    private String instruction;
    private RunStatus status;
    private String error;
    private String baseCommit;
    private String resultCommit;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    /** {@link Markup} as JSON; null for a plain text request. */
    private String markup;
}
