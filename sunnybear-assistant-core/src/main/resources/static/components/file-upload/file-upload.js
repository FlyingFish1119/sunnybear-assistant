/**
 * 文件上传组件（file chips + 隐藏文件选择器 + 文件读取 + 拖拽/粘贴接入）
 *
 * Props:
 *   files       — Array    当前已选文件列表（v-model）
 *   mainColor   — String   主题色
 *   dropZone    — String   拖拽区域的 CSS 选择器（不传则不启用拖拽）
 *   enablePaste — Boolean  是否启用粘贴上传，默认 false
 *
 * Emits:
 *   update-files     — 同步
 *   drag-over-change — 拖拽悬停状态变化，参数 (over: boolean)
 *
 * 用法示例：
 *   <file-upload ref="fileUpload"
 *       v-model:files="uploadedFiles"
 *       drop-zone=".message-area-wrapper"
 *       :enable-paste="true"
 *       :main-color="mainColor"
 *       @drag-over-change="dragOver = $event"
 *   ></file-upload>
 */
const FileUpload = {
    name: 'FileUpload',

    template: `
    <!-- 已选文件 chips -->
    <div v-if="files.length > 0" class="file-chips-area">
        <div v-for="(file, idx) in files" :key="idx" class="file-chip">
            <i :data-lucide="getFileIcon(file.name)" style="width:16px;height:16px"></i>
            <span class="file-chip-name">{{ file.name }}</span>
            <span class="file-chip-remove" @click="removeFile(idx)">
                <i data-lucide="x" style="width:14px;height:14px"></i>
            </span>
        </div>
        <span class="file-chip-add" @click="openFilePicker" title="添加更多文件">
            <i data-lucide="plus" style="width:16px;height:16px"></i>
        </span>
    </div>
    <!-- 隐藏文件选择器 -->
    <input type="file" ref="fileInput" multiple style="display:none" @change="onFileInputChange" />`,

    props: {
        files: { type: Array, default: function () { return []; } },
        mainColor: { type: String, default: 'lightsalmon' },
        dropZone: { type: String, default: '' },
        enablePaste: { type: Boolean, default: false },
    },

    emits: ['update-files', 'drag-over-change'],

    data: function () {
        return {
            dragCounter: 0,
        };
    },

    methods: {
        MAX_SIZE: 100 * 1024 * 1024, // 100MB

        /* ---- 文件读取 ---- */

        /**
         * 遍历文件列表，校验大小，通过 FileReader 读取为 data URL。
         * 拖拽 / 粘贴 / 文件选择器 统一走此入口。
         * @param {FileList|Array} fileList
         */
        processFiles: function (fileList) {
            let self = this;
            for (let i = 0; i < fileList.length; i++) {
                let file = fileList[i];
                if (file.size > self.MAX_SIZE) {
                    ElementPlus.ElMessage.warning('文件 "' + file.name + '" 超过 100MB 限制，已跳过');
                    continue;
                }
                let reader = new FileReader();
                reader.onload = function (ev) {
                    let newFiles = self.files.concat([{
                        name: file.name,
                        data: ev.target.result
                    }]);
                    self.$emit('update-files', newFiles);
                };
                reader.readAsDataURL(file);
            }
        },

        /**
         * 移除指定索引的文件
         */
        removeFile: function (idx) {
            let newFiles = this.files.slice();
            newFiles.splice(idx, 1);
            this.$emit('update-files', newFiles);
        },

        /**
         * 触发隐藏文件选择器
         */
        openFilePicker: function () {
            this.$refs.fileInput.click();
        },

        /**
         * 文件选择器 change 事件
         */
        onFileInputChange: function (e) {
            let files = e.target.files;
            if (files && files.length > 0) {
                this.processFiles(files);
            }
            e.target.value = '';
        },

        /* ---- 拖拽 ---- */

        onDragOver: function (e) {
            e.preventDefault();
        },

        onDragEnter: function (e) {
            e.preventDefault();
            this.dragCounter++;
            this.$emit('drag-over-change', true);
        },

        onDragLeave: function (e) {
            e.preventDefault();
            this.dragCounter--;
            if (this.dragCounter <= 0) {
                this.dragCounter = 0;
                this.$emit('drag-over-change', false);
            }
        },

        onDrop: function (e) {
            e.preventDefault();
            this.dragCounter = 0;
            this.$emit('drag-over-change', false);
            let files = e.dataTransfer.files;
            if (files && files.length > 0) {
                this.processFiles(files);
            }
        },

        /* ---- 粘贴 ---- */

        onPaste: function (e) {
            let items = e.clipboardData && e.clipboardData.items;
            if (!items) return;
            let fileList = [];
            for (var i = 0; i < items.length; i++) {
                if (items[i].kind === 'file') {
                    fileList.push(items[i].getAsFile());
                }
            }
            if (fileList.length > 0) {
                this.processFiles(fileList);
            }
        },

        /* ---- 图标 ---- */

        /**
         * 根据文件名后缀返回 Lucide 图标名
         */
        getFileIcon: function (filename) {
            let ext = (filename || '').split('.').pop().toLowerCase();
            let iconMap = {
                png: 'file-image', jpg: 'file-image', jpeg: 'file-image',
                gif: 'file-image', webp: 'file-image', bmp: 'file-image',
                svg: 'file-image', ico: 'file-image',
                mp3: 'file-audio', wav: 'file-audio', ogg: 'file-audio',
                flac: 'file-audio', aac: 'file-audio', wma: 'file-audio',
                mp4: 'file-video', webm: 'file-video', avi: 'file-video',
                mov: 'file-video', mkv: 'file-video', flv: 'file-video',
                pdf: 'file-text', txt: 'file-text', md: 'file-text',
                zip: 'file-archive', rar: 'file-archive', '7z': 'file-archive',
                tar: 'file-archive', gz: 'file-archive'
            };
            return iconMap[ext] || 'file';
        }
    },

    mounted: function () {
        let self = this;
        // 拖拽
        if (self.dropZone) {
            let zone = document.querySelector(self.dropZone);
            if (zone) {
                zone.addEventListener('dragover', self.onDragOver);
                zone.addEventListener('dragenter', self.onDragEnter);
                zone.addEventListener('dragleave', self.onDragLeave);
                zone.addEventListener('drop', self.onDrop);
                self._dropZoneEl = zone;
            }
        }
        // 粘贴
        if (self.enablePaste) {
            document.addEventListener('paste', self.onPaste);
        }
    },

    beforeUnmount: function () {
        // 拖拽解绑
        if (this._dropZoneEl) {
            this._dropZoneEl.removeEventListener('dragover', this.onDragOver);
            this._dropZoneEl.removeEventListener('dragenter', this.onDragEnter);
            this._dropZoneEl.removeEventListener('dragleave', this.onDragLeave);
            this._dropZoneEl.removeEventListener('drop', this.onDrop);
            this._dropZoneEl = null;
        }
        // 粘贴解绑
        if (this.enablePaste) {
            document.removeEventListener('paste', this.onPaste);
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
