package com.kiwi.keweiaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.kiwi.keweiaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 文件操作工具类
 */
@Component
public class FileOperationTool {

    private final String FILE_DIR = FileConstant.File_SAVE_DIR+"/file";

    /**
     * 从工具文件目录读取 UTF-8 文本。
     *
     * @param fileName 文件名
     * @return 文件内容或可供模型理解的错误文本
     */
    @Tool(description = "Read Content from a file",returnDirect = false)
    public String readFile(@ToolParam(description = "Name of a file to read") String fileName){
        String filePath = FILE_DIR + "/" + fileName;
        try {
            return FileUtil.readUtf8String(filePath);
        }catch (Exception e){
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * 将 UTF-8 文本写入工具文件目录，并在需要时创建目录。
     *
     * @param fileName 文件名
     * @param content 写入内容
     * @return 写入路径或错误文本
     */
    @Tool(description = "Write Content to a file")
    public String writeFile(@ToolParam(description = "Name of a file to write")String fileName,
                            @ToolParam(description = "Content to write to the file") String content){
        String filePath = FILE_DIR + "/" + fileName;
        try{
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, filePath);
            return "File written successfully to:" + filePath;
        }catch (Exception e){
            return "Error writing file: " + e.getMessage();
        }
    }
}
