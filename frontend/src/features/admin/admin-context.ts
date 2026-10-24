import { useOutletContext } from 'react-router'

export const useAdminUser = () => useOutletContext<string>()
