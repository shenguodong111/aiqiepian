package com.aiqiepian.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Renderer.render 输出：成片产物路径 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RenderResult {
    private String outputPath;
}
