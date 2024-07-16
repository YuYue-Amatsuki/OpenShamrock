package moe.fuqiuluo.qqinterface.servlet

import com.tencent.mobileqq.pb.ByteStringMicro
import moe.fuqiuluo.qqinterface.servlet.structures.*
import moe.fuqiuluo.qqinterface.servlet.transfile.RichProtoSvc
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.tools.EMPTY_BYTE_ARRAY
import moe.fuqiuluo.shamrock.tools.decodeToOidb
import moe.fuqiuluo.shamrock.tools.slice
import moe.fuqiuluo.shamrock.tools.toHexString
import moe.fuqiuluo.shamrock.utils.DeflateTools
import moe.fuqiuluo.shamrock.xposed.helper.QQInterfaces
import moe.fuqiuluo.symbols.decodeProtobuf
import protobuf.oidb.cmd0x6d7.CreateFolderReq
import protobuf.oidb.cmd0x6d7.DeleteFolderReq
import protobuf.oidb.cmd0x6d7.MoveFolderReq
import protobuf.oidb.cmd0x6d7.Oidb0x6d7ReqBody
import protobuf.oidb.cmd0x6d7.Oidb0x6d7RespBody
import protobuf.oidb.cmd0x6d7.RenameFolderReq
import tencent.im.oidb.cmd0x6d6.oidb_0x6d6
import tencent.im.oidb.cmd0x6d8.oidb_0x6d8
import tencent.im.oidb.oidb_sso
import protobuf.group_file_common.FolderInfo as GroupFileCommonFolderInfo
import protobuf.auto.toByteArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object FileSvc: QQInterfaces() {
    suspend fun createFileFolder(groupId: Long, folderName: String, parentFolderId: String = "/"): Result<GroupFileCommonFolderInfo> {
        val data = Oidb0x6d7ReqBody(
            createFolder = CreateFolderReq(
                groupCode = groupId.toULong(),
                appId = 3u,
                parentFolderId = parentFolderId,
                folderName = folderName
            )
        ).toByteArray()
        val fromServiceMsg = sendOidbAW("OidbSvc.0x6d7_0", 1751, 0, data)
            ?: return Result.failure(Exception("unable to fetch result"))
        val oidbPkg = fromServiceMsg.decodeToOidb()
        val rsp = oidbPkg.bytes_bodybuffer.get()
            .toByteArray()
            .decodeProtobuf<Oidb0x6d7RespBody>()
        if (rsp.createFolder?.retCode != 0) {
            return Result.failure(Exception("unable to create folder: ${rsp.createFolder?.retCode}"))
        }
        return Result.success(rsp.createFolder!!.folderInfo!!)
    }

    suspend fun deleteGroupFolder(groupId: Long, folderUid: String): Boolean {
        val fromServiceMsg = sendOidbAW("OidbSvc.0x6d7_1", 1751, 1, Oidb0x6d7ReqBody(
            deleteFolder = DeleteFolderReq(
                groupCode = groupId.toULong(),
                appId = 3u,
                folderId = folderUid
            )
        ).toByteArray()) ?: return false
        val oidbPkg = fromServiceMsg.decodeToOidb()
        val rsp = oidbPkg.bytes_bodybuffer.get().toByteArray().decodeProtobuf<Oidb0x6d7RespBody>()
        return rsp.deleteFolder?.retCode == 0
    }

    suspend fun moveGroupFolder(groupId: Long, folderUid: String, newParentFolderUid: String): Boolean {
        val fromServiceMsg = sendOidbAW("OidbSvc.0x6d7_2", 1751, 2, Oidb0x6d7ReqBody(
            moveFolder = MoveFolderReq(
                groupCode = groupId.toULong(),
                appId = 3u,
                folderId = folderUid,
                parentFolderId = "/"
            )
        ).toByteArray()) ?: return false
        val oidbPkg = fromServiceMsg.decodeToOidb()
        val rsp = oidbPkg.bytes_bodybuffer.get().toByteArray().decodeProtobuf<Oidb0x6d7RespBody>()
        return rsp.moveFolder?.retCode == 0
    }

    suspend fun renameFolder(groupId: Long, folderUid: String, name: String): Boolean {
        val fromServiceMsg = sendOidbAW("OidbSvc.0x6d7_3", 1751, 3, Oidb0x6d7ReqBody(
            renameFolder = RenameFolderReq(
                groupCode = groupId.toULong(),
                appId = 3u,
                folderId = folderUid,
                folderName = name
            )
        ).toByteArray()) ?: return false
        val oidbPkg = fromServiceMsg.decodeToOidb()
        val rsp = oidbPkg.bytes_bodybuffer.get().toByteArray().decodeProtobuf<Oidb0x6d7RespBody>()
        return rsp.renameFolder?.retCode == 0
    }

    suspend fun deleteGroupFile(groupId: Long, bizId: Int, fileUid: String): Boolean {
        val oidb0x6d6ReqBody = oidb_0x6d6.ReqBody().apply {
            delete_file_req.set(oidb_0x6d6.DeleteFileReqBody().apply {
                uint64_group_code.set(groupId)
                uint32_app_id.set(3)
                uint32_bus_id.set(bizId)
                str_parent_folder_id.set("/")
                str_file_id.set(fileUid)
            })
        }
        val result = sendOidbAW("OidbSvc.0x6d6_3", 1750, 3, oidb0x6d6ReqBody.toByteArray())
            ?: return false
        val oidbPkg = result.decodeToOidb()
        val rsp = oidb_0x6d6.RspBody().apply {
            mergeFrom(oidbPkg.bytes_bodybuffer.get().toByteArray())
        }
        return rsp.delete_file_rsp.int32_ret_code.get() == 0
    }

    suspend fun getGroupFileSystemInfo(groupId: Long): FileSystemInfo {
        val rspGetFileCntBuffer = sendOidbAW("OidbSvc.0x6d8_1", 1752, 2, oidb_0x6d8.ReqBody().also {
            it.group_file_cnt_req.set(oidb_0x6d8.GetFileCountReqBody().also {
                it.uint64_group_code.set(groupId)
                it.uint32_app_id.set(3)
                it.uint32_bus_id.set(0)
            })
        }.toByteArray())
        val fileCnt: Int
        val limitCnt: Int
        if (rspGetFileCntBuffer != null) {
            oidb_0x6d8.RspBody().mergeFrom(
                rspGetFileCntBuffer.decodeToOidb()
                .bytes_bodybuffer.get()
                .toByteArray()
            ).group_file_cnt_rsp.apply {
                fileCnt = uint32_all_file_count.get()
                limitCnt = uint32_limit_count.get()
            }
        } else {
            throw RuntimeException("获取群文件数量失败")
        }

        val rspGetFileSpaceBuffer = sendOidbAW("OidbSvc.0x6d8_1", 1752, 3, oidb_0x6d8.ReqBody().also {
            it.group_space_req.set(oidb_0x6d8.GetSpaceReqBody().apply {
                uint64_group_code.set(groupId)
                uint32_app_id.set(3)
            })
        }.toByteArray())
        val totalSpace: Long
        val usedSpace: Long
        if (rspGetFileSpaceBuffer != null) {
            oidb_0x6d8.RspBody().mergeFrom(
                rspGetFileSpaceBuffer.decodeToOidb()
                .bytes_bodybuffer.get()
                .toByteArray()).group_space_rsp.apply {
                totalSpace = uint64_total_space.get()
                usedSpace = uint64_used_space.get()
            }
        } else {
            throw RuntimeException("获取群文件空间失败")
        }

        return FileSystemInfo(
            fileCnt, limitCnt, usedSpace, totalSpace
        )
    }

    suspend fun getGroupRootFiles(groupId: Long): Result<GroupFileList> {
        return getGroupFiles(groupId, "/")
    }

    suspend fun getGroupFileInfo(groupId: Long, fileId: String, busid: Int): FileUrl {
        return FileUrl(RichProtoSvc.getGroupFileDownUrl(groupId, fileId, busid))
    }

    suspend fun getGroupFiles(groupId: Long, folderId: String): Result<GroupFileList> {
        val fileSystemInfo = getGroupFileSystemInfo(groupId)
        val rspGetFileListBuffer = sendOidbAW("OidbSvc.0x6d8_1", 1752, 1, oidb_0x6d8.ReqBody().also {
            it.file_list_info_req.set(oidb_0x6d8.GetFileListReqBody().apply {
                uint64_group_code.set(groupId)
                uint32_app_id.set(3)
                str_folder_id.set(folderId)

                uint32_file_count.set(fileSystemInfo.fileCount)
                uint32_all_file_count.set(0)
                uint32_req_from.set(3)
                uint32_sort_by.set(oidb_0x6d8.GetFileListReqBody.SORT_BY_TIMESTAMP)

                uint32_filter_code.set(0)
                uint64_uin.set(0)

                uint32_start_index.set(0)

                bytes_context.set(ByteStringMicro.copyFrom(EMPTY_BYTE_ARRAY))

                uint32_show_onlinedoc_folder.set(0)
            })
        }.toByteArray(), timeout = 15.seconds)

        return kotlin.runCatching {
            val files = arrayListOf<FileInfo>()
            val dirs = arrayListOf<FolderInfo>()
            if (rspGetFileListBuffer != null) {
                val oidb = rspGetFileListBuffer.decodeToOidb()

                oidb_0x6d8.RspBody().mergeFrom(oidb.bytes_bodybuffer.get().toByteArray())
                    .file_list_info_rsp.apply {
                    rpt_item_list.get().forEach { file ->
                        if (file.uint32_type.get() == oidb_0x6d8.GetFileListRspBody.TYPE_FILE) {
                            val fileInfo = file.file_info
                            files.add(FileInfo(
                                groupId = groupId,
                                fileId = fileInfo.str_file_id.get(),
                                fileName = fileInfo.str_file_name.get(),
                                fileSize = fileInfo.uint64_file_size.get(),
                                busid = fileInfo.uint32_bus_id.get(),
                                uploadTime = fileInfo.uint32_upload_time.get(),
                                deadTime = fileInfo.uint32_dead_time.get(),
                                modifyTime = fileInfo.uint32_modify_time.get(),
                                downloadTimes = fileInfo.uint32_download_times.get(),
                                uploadUin = fileInfo.uint64_uploader_uin.get(),
                                uploadNick = fileInfo.str_uploader_name.get(),
                                md5 = fileInfo.bytes_md5.get().toByteArray().toHexString(),
                                sha = fileInfo.bytes_sha.get().toByteArray().toHexString(),
                                // 根本没有
                                sha3 = fileInfo.bytes_sha3.get().toByteArray().toHexString(),
                            ))
                        }
                        else if (file.uint32_type.get() == oidb_0x6d8.GetFileListRspBody.TYPE_FOLDER) {
                            val folderInfo = file.folder_info
                            dirs.add(FolderInfo(
                                groupId = groupId,
                                folderId = folderInfo.str_folder_id.get(),
                                folderName = folderInfo.str_folder_name.get(),
                                totalFileCount = folderInfo.uint32_total_file_count.get(),
                                createTime = folderInfo.uint32_create_time.get(),
                                creator = folderInfo.uint64_create_uin.get(),
                                creatorNick = folderInfo.str_creator_name.get()
                            ))
                        } else {
                            LogCenter.log("未知文件类型: ${file.uint32_type.get()}", Level.WARN)
                        }
                    }
                }
            } else {
                throw RuntimeException("获取群文件列表失败")
            }

            GroupFileList(files, dirs)
        }.onFailure {
            LogCenter.log(it.message + ", buffer: ${rspGetFileListBuffer?.wupBuffer?.toHexString()}", Level.ERROR)
        }
    }
}