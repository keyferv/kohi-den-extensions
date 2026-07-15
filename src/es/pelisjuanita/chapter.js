const chapterHtml = {
    renderChaptersByVolume: function (data) {
        let html = '';
        data.forEach((volumeBlock, vIdx) => {
            const expanded = vIdx === 0 ? 'expanded' : 'collapsed';
            html += `<div class="volume-block mb-2 ${expanded}" data-volume="${volumeBlock.volume}">
                    <div class="volume-header fw-bold fs-5 mb-2 d-flex align-items-center justify-content-between" style="cursor:pointer;" onclick="toggleVolume(${volumeBlock.volume})">
                        <span class="text-white"><i class="fas fa-book me-2"></i>Volume ${volumeBlock.volume}</span>
                        <button class="btn btn-sm btn-outline-light volume-toggle-btn" tabindex="-1">
                            <i class="fas fa-chevron-${expanded === 'expanded' ? 'up' : 'down'}"></i>
                        </button>
                    </div>
                    <div class="volume-chapters" style="display:${expanded === 'expanded' ? 'block' : 'none'};">`;
            volumeBlock.chapters.forEach((chapter, idx) => {
                html += `<div class="chapter-block mb-2 p-1 rounded-3 bg-dark bg-opacity-75">
                        <div class="d-flex align-items-center justify-content-between">
                            <div class="chapter-number fw-bold">${chapter.number}</div>
                        </div>
                        <div class="chapter-translations mt-2">`;
                chapter.translations.forEach(tr => {
                    html += `<div class="chapter-translation d-flex align-items-center gap-3 mb-2 p-2 rounded-2 bg-secondary bg-opacity-10 flex-wrap" data-chapter-id="${tr.id}">
                            <span class="badge" style="background:${renderHtml.renderGetLanguageColor(tr.language)};color:#fff;">${renderHtml.renderGetLanguageName(tr.language)}</span>
                            <span class="group small text-primary"><i class="fas fa-pen me-1"></i>${tr.name != null ? tr.name : 'Chapter ' + chapter.number_float}</span>
                            <span class="group small text-info"><i class="fas fa-users me-1"></i>${tr.group}</span>
                            <span class="date small text-secondary"><i class="fas fa-clock me-1"></i>${renderHtml.renderGetTimeAgo(tr.date)}</span>
                            <span class="views small text-secondary"><i class="fas fa-eye me-1"></i>${renderHtml.renderFormatNumber(tr.views)}</span>
                            <span class="likes small text-danger" style="cursor: pointer;" onclick="callLikes(this, '${tr.id}')"><i class="fas fa-heart me-1"></i>${renderHtml.renderFormatNumber(tr.likes)}</span>
                            <span class="comments small text-info" onclick="showComments(this, '${tr.id}')"><i class="fas fa-comment me-1"></i>${renderHtml.renderFormatNumber(tr.comments)}</span>
                            <button class="btn btn-sm btn-outline-primary ms-auto btn-read" onclick="window.location.href='${tr.url}'" ><i class="fas fa-book-open me-1"></i>Read Now</button>
                        </div>`;
                });
                html += `</div></div>`;
            });
            html += `</div></div>`;
        });
        return html;
    },
    renderGenerateVolumeTab: function (volume, isCurrent = false) {
        let html = '';
        html += `
            <li class="nav-item" role="presentation">
                <button class="nav-link ${isCurrent ? 'active' : ''}" 
                        id="volume-${volume.volume}-tab" 
                        data-bs-toggle="tab" 
                        data-bs-target="#volume-${volume.volume}" 
                        type="button" 
                        role="tab">
                        Volume ${volume.volume}
                </button>
            </li>
        `;
        return html;
    },
    renderGenerateVolumeContent: function (volume, isCurrent = false, currentChapter = 0, currentTranslation = '') {
        let html = '';
        html += `
            <div class="tab-pane fade ${isCurrent ? 'show active' : ''}" id="volume-${volume.volume}" role="tabpanel">
                <div class="volume-info">
                    <p class="text-secondary mb-0">Total: ${volume.chapters.length} chapters</p>
                </div>
                <div class="chapters-list">
                    ${this.renderGenerateChapters(volume.chapters, currentChapter, currentTranslation)}
                </div>
            </div>
        `;
        return html;
    },
    renderGenerateChapters: function (chapters, currentChapter = 0, currentTranslation = '') {
        let html = '';
        chapters.forEach(chapter => {
            const isCurrent = parseFloat(chapter.number_float) === parseFloat(currentChapter);
            html += `
                    <div class="chapter-item ${isCurrent ? 'current' : ''}" 
                         data-volume="${chapter.volume}" 
                         data-chapter="${chapter.number_float}">
                        <div class="d-flex justify-content-between align-items-start mb-2">
                            <div>
                                <h6 class="mb-1">${chapter.number}</h6>
                                <small class="text-secondary">${chapter.translations.length} translations</small>
                            </div>
                            ${isCurrent ? '<span class="badge bg-primary">Reading</span>' : ''}
                        </div>
                        <div class="translations-list">
                            ${chapterHtml.renderGenerateTranslations(chapter.translations, chapter.number_float, currentTranslation)}
                        </div>
                    </div>
                `;
        });
        return html;
    },
    
    renderGenerateTranslations: function (translations, chapterNumber, currentTranslation = '') {
        let html = '';
        translations.forEach(translation => {
            const isCurrent = String(translation.id) === String(currentTranslation);
            html += `
                    <div class="translation-item ${isCurrent ? 'current' : ''}" 
                        data-chapter="${chapterNumber}" 
                        data-language="${translation.language}" 
                        data-url="${translation.url}"
                         onclick="window.location.href = '${translation.url}'">
                        <div class="d-flex justify-content-between align-items-center">
                            <div class="d-flex align-items-center gap-2">
                                <span class="language-badge" style="background: ${renderHtml.renderGetLanguageColor(translation.language)};">
                                    ${translation.languageName}
                                </span>
                                <span class="text-secondary">${translation.group}</span>
                            </div>
                            <div class="d-flex align-items-center gap-2">
                                <small class="text-secondary">
                                    <i class="fas fa-eye me-1"></i>${renderHtml.renderFormatNumber(translation.views)}
                                </small>
                                <small class="text-secondary">
                                    <i class="fas fa-clock me-1"></i>${renderHtml.renderGetTimeAgo(translation.date)}
                                </small>
                            </div>
                        </div>
                    </div>
                `;
        });
        return html;
    },
    

    

    
    renderGenerateTranslationsNoVolume: function (translations, chapterNumber, currentTranslation = '') {
        let html = '';
        translations.forEach(translation => {
            const isCurrent = String(translation.id) === String(currentTranslation);
            html += `
                    <div class="translation-item ${isCurrent ? 'current' : ''}" 
                        data-chapter="${chapterNumber}" 
                        data-language="${translation.language}" 
                        data-url="${translation.url}"
                         onclick="window.location.href = '${translation.url}'">
                        <div class="d-flex justify-content-between align-items-center">
                            <div class="d-flex align-items-center gap-2">
                                <span class="language-badge" style="background: ${renderHtml.renderGetLanguageColor(translation.language)};">
                                    ${translation.languageName}
                                </span>
                                <span class="text-secondary">${translation.group}</span>
                            </div>
                            <div class="d-flex align-items-center gap-2">
                                <small class="text-secondary">
                                    <i class="fas fa-eye me-1"></i>${renderHtml.renderFormatNumber(translation.views)}
                                </small>
                                <small class="text-secondary">
                                    <i class="fas fa-clock me-1"></i>${renderHtml.renderGetTimeAgo(translation.date)}
                                </small>
                            </div>
                        </div>
                    </div>
                `;
        });
        return html;
    },
    renderImageChapterVertical: function (imageUrls) {
        let html = '';
        imageUrls.forEach((imageUrl, index) => {
            // Tạo placeholder cho lazy loading
            const placeholderSvg = `
                <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="600" viewBox="0 0 400 600">
                    <rect width="100%" height="100%" fill="#f8f9fa"/>
                    <text x="50%" y="50%" text-anchor="middle" dy=".3em" fill="#6c757d" font-family="Arial" font-size="16">
                        Loading page ${index + 1}...
                    </text>
                </svg>
            `;
            const placeholder = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(placeholderSvg)}`;
            
            html += `
                    <div class="manga-page w-100" data-page="${index + 1}">
                        <div class="page-container w-100">
                            <img src="${placeholder}" 
                                 data-src="${imageUrl}" 
                                 alt="Trang ${index + 1}" 
                                 class="manga-image w-100 img-fluid lazy-load" 
                                 style="transition: opacity 0.3s ease;"
                                 loading="lazy">
                            <div class="page-overlay">
                                <div class="page-number">${index + 1}</div>
                            </div>
                        </div>
                    </div>
                `;
            
        });
        return html;
    },
    renderImageChapterHorizontal: function (imageUrls) {
        let html = '';
        imageUrls.forEach((imageUrl, index) => {
            // Tạo placeholder cho lazy loading
            const placeholderSvg = `
                <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="400" viewBox="0 0 400 400">
                    <rect width="100%" height="100%" fill="#f8f9fa"/>
                    <text x="50%" y="50%" text-anchor="middle" dy=".3em" fill="#6c757d" font-family="Arial" font-size="16">
                        Loading page ${index + 1}...
                    </text>
                </svg>
            `;
            const placeholder = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(placeholderSvg)}`;
            
            html += `
                <div class="justify-content-center align-items-center manga-page ${index === 0 ? 'currentPage' : ''}"
                 style="display: ${index === 0 ? 'flex' : 'none'};" data-page="${index + 1}">
                    <img src="${placeholder}" 
                         data-src="${imageUrl}" 
                         class="img-fluid lazy-load" 
                         style="object-fit: contain; transition: opacity 0.3s ease;"
                         loading="lazy"
                         alt="Trang ${index + 1}">
                    <div class="page-overlay">
                        <div class="page-number">${index + 1}</div>
                    </div>
                </div>
            `;
        });
        return html;
    }
};



// SweetAlert Dark Theme CSS
// const swalDarkCSS = `

// `;

// // Inject CSS if not already present
// if (!document.getElementById('swal-dark-css')) {
//     const style = document.createElement('div');
//     style.id = 'swal-dark-css';
//     style.innerHTML = swalDarkCSS;
//     document.head.appendChild(style);
// }

window.chapterHtml = chapterHtml;