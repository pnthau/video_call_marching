/**
 * MARCHING LANGUAGE - Reusable Criteria Cascading Selector
 * Manages dynamic synchronization between:
 *   1. Language select
 *   2. Level select (JLPT N5-N1 for ja, CEFR A1-C1 for en)
 *   3. Topic select (filtered by language + level with lesson counts)
 */
const CriteriaSelector = (function () {
    const LEVEL_CONFIGS = {
        ja: [
            { value: 'all', label: '🌐 Tất cả trình độ (Tiếng Nhật)' },
            { value: 'n5', label: '🟢 N5 - Nhập môn (Beginner)' },
            { value: 'n4', label: '🔵 N4 - Sơ cấp (Elementary)' },
            { value: 'n3', label: '🟡 N3 - Trung cấp (Intermediate)' },
            { value: 'n2', label: '🟠 N2 - Trung cao cấp (Upper-Intermediate)' },
            { value: 'n1', label: '🔴 N1 - Cao cấp (Advanced)' }
        ],
        en: [
            { value: 'all', label: '🌐 Tất cả trình độ (Tiếng Anh)' },
            { value: 'a1', label: '🟢 A1 - Cơ bản (Beginner)' },
            { value: 'a2', label: '🔵 A2 - Sơ cấp (Elementary)' },
            { value: 'b1', label: '🟡 B1 - Trung cấp (Intermediate)' },
            { value: 'b2', label: '🟠 B2 - Tự tin (Upper-Intermediate)' },
            { value: 'c1', label: '🔴 C1 - Nâng cao (Advanced)' }
        ]
    };

    /**
     * Initializes a 3-step cascading criteria selector (Language -> Level -> Topic).
     *
     * @param {Object} options
     * @param {string|HTMLElement} options.langSelect - ID or Element for Language select
     * @param {string|HTMLElement} options.levelSelect - ID or Element for Level select
     * @param {string|HTMLElement} options.topicSelect - ID or Element for Topic select
     * @param {Array|string} options.topicsData - Array of topic objects or JSON string
     * @param {string} [options.itemSuffix='bài học'] - Suffix for lesson count display (e.g. 'bài học', 'bài nghe')
     */
    function initCascadingSelect(options) {
        if (!options) return;

        const langSelect = typeof options.langSelect === 'string'
            ? document.getElementById(options.langSelect)
            : options.langSelect;
        const levelSelect = typeof options.levelSelect === 'string'
            ? document.getElementById(options.levelSelect)
            : options.levelSelect;
        const topicSelect = typeof options.topicSelect === 'string'
            ? document.getElementById(options.topicSelect)
            : options.topicSelect;

        if (!langSelect || !levelSelect || !topicSelect) return;

        let topicsData = options.topicsData || [];
        if (typeof topicsData === 'string') {
            try {
                topicsData = JSON.parse(topicsData);
            } catch (e) {
                console.warn('CriteriaSelector: Failed to parse topicsData JSON:', e);
                topicsData = [];
            }
        }
        if (!Array.isArray(topicsData)) {
            topicsData = [];
        }

        const itemSuffix = options.itemSuffix || 'bài học';

        function updateLevelDropdown(selectedLang, targetLevel) {
            const lang = (selectedLang || 'all').trim().toLowerCase();
            const currentLevel = (targetLevel || 'all').trim().toLowerCase();

            levelSelect.innerHTML = '';
            let validLevels = [];
            if (lang === 'ja') {
                validLevels = LEVEL_CONFIGS.ja;
            } else if (lang === 'en') {
                validLevels = LEVEL_CONFIGS.en;
            } else {
                validLevels = [
                    { value: 'all', label: '🌐 Tất cả trình độ' },
                    { optgroup: '🇯🇵 Tiếng Nhật (JLPT)', items: LEVEL_CONFIGS.ja.filter(x => x.value !== 'all') },
                    { optgroup: '🇬🇧 Tiếng Anh (CEFR)', items: LEVEL_CONFIGS.en.filter(x => x.value !== 'all') }
                ];
            }

            let isLevelFound = false;
            validLevels.forEach(item => {
                if (item.optgroup) {
                    const group = document.createElement('optgroup');
                    group.label = item.optgroup;
                    item.items.forEach(sub => {
                        const opt = document.createElement('option');
                        opt.value = sub.value;
                        opt.textContent = sub.label;
                        if (sub.value.toLowerCase() === currentLevel) {
                            opt.selected = true;
                            isLevelFound = true;
                        }
                        group.appendChild(opt);
                    });
                    levelSelect.appendChild(group);
                } else {
                    const opt = document.createElement('option');
                    opt.value = item.value;
                    opt.textContent = item.label;
                    if (item.value.toLowerCase() === currentLevel) {
                        opt.selected = true;
                        isLevelFound = true;
                    }
                    levelSelect.appendChild(opt);
                }
            });

            if (!isLevelFound) {
                levelSelect.value = 'all';
            }
        }

        function updateTopicDropdown(selectedLang, selectedLevel, targetTagId) {
            const lang = (selectedLang || 'all').trim().toLowerCase();
            const lvl = (selectedLevel || 'all').trim().toLowerCase();

            const tagMap = new Map();
            let totalItemsForCombo = 0;

            topicsData.forEach(item => {
                const itemLang = (item.language || '').trim().toLowerCase();
                const itemLvl = (item.level || '').trim().toLowerCase();
                const count = parseInt(item.scriptCount ?? item.audioLessonCount ?? item.count, 10) || 0;

                const matchesLang = (lang === 'all' || itemLang === lang);
                const matchesLevel = (lvl === 'all' || itemLvl === lvl);

                if (matchesLang && matchesLevel) {
                    totalItemsForCombo += count;
                    if (!tagMap.has(item.tagId)) {
                        tagMap.set(item.tagId, {
                            id: item.tagId,
                            name: item.tagName,
                            count: 0
                        });
                    }
                    tagMap.get(item.tagId).count += count;
                }
            });

            topicSelect.innerHTML = '';
            const defaultOption = document.createElement('option');
            defaultOption.value = '';
            defaultOption.textContent = `Tất cả chủ đề (Tổng ${totalItemsForCombo} ${itemSuffix})`;
            topicSelect.appendChild(defaultOption);

            const sortedTopics = Array.from(tagMap.values())
                .filter(t => t.count > 0)
                .sort((a, b) => (a.name || '').localeCompare(b.name || '', 'vi'));

            let isTargetFound = false;
            sortedTopics.forEach(topic => {
                const opt = document.createElement('option');
                opt.value = topic.id;
                opt.textContent = `${topic.name} (${topic.count} ${itemSuffix})`;
                if (targetTagId && String(topic.id) === String(targetTagId)) {
                    opt.selected = true;
                    isTargetFound = true;
                }
                topicSelect.appendChild(opt);
            });

            if (!isTargetFound) {
                defaultOption.selected = true;
            }
        }

        // Determine initial values
        const initialLang = langSelect.value || 'all';
        const initialLevel = levelSelect.getAttribute('data-current-level') || levelSelect.value || 'all';
        const initialTagId = topicSelect.getAttribute('data-current-tag-id') || topicSelect.value || '';

        updateLevelDropdown(initialLang, initialLevel);
        updateTopicDropdown(initialLang, levelSelect.value || initialLevel, initialTagId);

        langSelect.addEventListener('change', function () {
            updateLevelDropdown(this.value, 'all');
            updateTopicDropdown(this.value, levelSelect.value, '');
        });

        levelSelect.addEventListener('change', function () {
            updateTopicDropdown(langSelect.value, this.value, '');
        });
    }

    return {
        initCascadingSelect: initCascadingSelect,
        LEVEL_CONFIGS: LEVEL_CONFIGS
    };
})();
