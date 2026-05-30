import { BrowserRouter, Route, Routes } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import ArtistDetailPage from './pages/ArtistDetailPage'
import EmsAcquisitionAdminPage from './pages/EmsAcquisitionAdminPage'
import EmsPage from './pages/EmsPage'
import EmsPlaylistDetailPage from './pages/EmsPlaylistDetailPage'
import EmsPoolAdminPage from './pages/EmsPoolAdminPage'
import EmsSearchPlaylistDetailPage from './pages/EmsSearchPlaylistDetailPage'
import FeatureCoverageAdminPage from './pages/FeatureCoverageAdminPage'
import GmsPlaylistsPage from './pages/GmsPlaylistsPage'
import GmsPreviewPage from './pages/GmsPreviewPage'
import HomePage from './pages/HomePage'
import MelonHot100Page from './pages/MelonHot100Page'
import NotFoundPage from './pages/NotFoundPage'
import RecommendationAlgorithmPage from './pages/RecommendationAlgorithmPage'
import PlaybackHarnessPage from './pages/PlaybackHarnessPage'
import MetadataNormalizationAdminPage from './pages/MetadataNormalizationAdminPage'
import PlaylistQualityAdminPage from './pages/PlaylistQualityAdminPage'
import PublicCurationAdminPage from './pages/PublicCurationAdminPage'
import PublicCurationSharePage from './pages/PublicCurationSharePage'
import SasrecModelAdminPage from './pages/SasrecModelAdminPage'
import SchedulingAdminPage from './pages/SchedulingAdminPage'
import PmsPlaylistDetailPage from './pages/PmsPlaylistDetailPage'
import PmsPage from './pages/PmsPage'
import PlatformsPage from './pages/PlatformsPage'
import TidalPlaylistPlaybackTestPage from './pages/TidalPlaylistPlaybackTestPage'
import VisualizerPage from './pages/VisualizerPage'
import Login from './pages/auth/Login'
import Register from './pages/auth/Register'
import PlatformOAuthCallbackPage from './pages/platforms/PlatformOAuthCallbackPage'

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<MainLayout />}>
                    <Route index element={<HomePage />} />
                    <Route path="platforms" element={<PlatformsPage />} />
                    <Route path="platforms/oauth/callback" element={<PlatformOAuthCallbackPage />} />
                    <Route path="pms" element={<PmsPage />} />
                    <Route path="ems" element={<EmsPage />} />
                    <Route path="ems/acquisition-admin" element={<EmsAcquisitionAdminPage />} />
                    <Route path="ems/pool-admin" element={<EmsPoolAdminPage />} />
                    <Route path="recommendations/quality-admin" element={<PlaylistQualityAdminPage />} />
                    <Route path="recommendations/feature-coverage" element={<FeatureCoverageAdminPage />} />
                    <Route path="recommendations/sasrec-admin" element={<SasrecModelAdminPage />} />
                    <Route path="recommendations/metadata-admin" element={<MetadataNormalizationAdminPage />} />
                    <Route path="admin/schedules" element={<SchedulingAdminPage />} />
                    <Route path="admin/public-curations" element={<PublicCurationAdminPage />} />
                    <Route path="gms-playlists" element={<GmsPlaylistsPage />} />
                    <Route path="gms-preview" element={<GmsPreviewPage />} />
                    <Route path="playback-harness" element={<PlaybackHarnessPage />} />
                    <Route path="artists/:artistSlug" element={<ArtistDetailPage />} />
                    <Route path="ems/search/playlists/:platformId/:externalPlaylistId" element={<EmsSearchPlaylistDetailPage />} />
                    <Route path="playlists/ems/:playlistId" element={<EmsPlaylistDetailPage />} />
                    <Route path="playlists/pms/:playlistId" element={<PmsPlaylistDetailPage />} />
                    <Route path="melon-hot-100" element={<MelonHot100Page />} />
                    <Route path="about/recommendation" element={<RecommendationAlgorithmPage />} />
                </Route>
                <Route path="login" element={<Login />} />
                <Route path="signin" element={<Login />} />
                <Route path="signup" element={<Register />} />
                <Route path="register" element={<Register />} />
                <Route path="tidal-playlist-test" element={<TidalPlaylistPlaybackTestPage />} />
                <Route path="visualizer" element={<VisualizerPage />} />
                <Route path="mix/:slug" element={<PublicCurationSharePage />} />
                <Route path="share/playlists/:slug" element={<PublicCurationSharePage />} />
                <Route path="*" element={<NotFoundPage />} />
            </Routes>
        </BrowserRouter>
    )
}

export default App
