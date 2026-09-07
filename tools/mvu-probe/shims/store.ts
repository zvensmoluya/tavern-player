// Only settings read by the selected upstream entry points. No Vue/Pinia UI.
const settings = {
    通知: { 变量更新出错: true, 变量初始化成功: false },
    兼容性: { 更新到聊天变量: false },
};
export const useDataStore = () => ({ settings, effective_settings: settings });
